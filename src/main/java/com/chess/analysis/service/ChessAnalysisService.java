package com.chess.analysis.service;

import com.chess.analysis.model.AnalysisResponse;
import com.chess.analysis.model.MoveReview;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChessAnalysisService {

    private final StockfishService stockfishService;
    private final OpeningBookService openingBookService;
    // Limit threads to avoid crashing your PC.
    // Using N-1 cores to keep the system responsive.
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

    public ChessAnalysisService(StockfishService stockfishService, OpeningBookService openingBookService) {
        this.stockfishService = stockfishService;
        this.openingBookService = openingBookService;
    }

    public AnalysisResponse analyzeGame(String pgnContent) throws Exception {
        File tempFile = File.createTempFile("game", ".pgn");
        Files.write(tempFile.toPath(), pgnContent.getBytes());

        PgnHolder holder = new PgnHolder(tempFile.getAbsolutePath());
        holder.loadPgn();
        var game = holder.getGames().getFirst();

        Board board = new Board();
        List<String> fensToEvaluate = new ArrayList<>();
        List<String> moveSans = new ArrayList<>();
        List<Boolean> sideToMove = new ArrayList<>();

        // 1. Pre-calculate FENs for all moves (Fast)
        fensToEvaluate.add(board.getFen()); // Start position
        for (Move move : game.getHalfMoves()) {
            sideToMove.add(board.getFen().contains(" w "));
            moveSans.add(move.toString());
            board.doMove(move);
            fensToEvaluate.add(board.getFen());
        }

        // Extract [%clk ...] timestamps from the raw PGN text
        List<String> clockTimes = new ArrayList<>();
        // Flexible pattern: match any combination of digits, colons, and dots inside
        // [%clk ...]
        Pattern clkPattern = Pattern.compile("\\[%clk\\s+([\\d:.]+)\\]");
        Matcher clkMatcher = clkPattern.matcher(pgnContent);
        while (clkMatcher.find()) {
            String raw = clkMatcher.group(1);
            // Optional: strip leading "0:" or "00:" if desired, but let's keep it robust
            // Most chess sites use "0:05:00". If it starts with "0:", stripping it makes it
            // "05:00"
            if (raw.startsWith("0:")) {
                raw = raw.substring(2);
            } else if (raw.startsWith("00:")) {
                raw = raw.substring(3);
            }
            clockTimes.add(raw);
        }

        // 2. Evaluate all FENs in parallel (The bottleneck)
        // Lowered depth to 14 for speed; it's plenty for accuracy scores.
        int depth = 14;
        List<CompletableFuture<StockfishService.EvalResult>> futures = fensToEvaluate.stream()
                .map(fen -> CompletableFuture.supplyAsync(() -> {
                    try {
                        int rawEval = stockfishService.evaluatePosition(fen, depth);
                        // Normalise so positive = good for side to move
                        int normalized = fen.contains(" w ") ? rawEval : -rawEval;
                        // We'll resolve the bestMove separately for each pre-move FEN
                        return new StockfishService.EvalResult(normalized, null);
                    } catch (Exception e) {
                        return new StockfishService.EvalResult(0, null);
                    }
                }, executor))
                .toList();

        // Wait for all evaluations to finish
        List<StockfishService.EvalResult> evalResults = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        List<Integer> evaluations = evalResults.stream().map(StockfishService.EvalResult::evaluation).toList();

        // 3. Process results and calculate CP Loss
        List<MoveReview> reviews = new ArrayList<>();
        int totalCpLoss = 0;
        boolean inBook = true;

        for (int i = 0; i < moveSans.size(); i++) {
            int evalBefore = evaluations.get(i);
            int evalAfter = evaluations.get(i + 1);
            boolean isWhite = sideToMove.get(i);

            int cpLoss = isWhite ? Math.max(0, evalBefore - evalAfter) : Math.max(0, evalAfter - evalBefore);
            String fenAfter = fensToEvaluate.get(i + 1);

            // -- Brilliant Move Sacrifice Detection --
            Board tempBoard = new Board();
            tempBoard.loadFromFen(fensToEvaluate.get(i));
            int b1 = calculateMaterialBalance(tempBoard, isWhite);

            tempBoard.loadFromFen(fenAfter);
            int b2 = calculateMaterialBalance(tempBoard, isWhite);
            int maxLoss = calculateMaxMaterialLoss(tempBoard, isWhite);
            int b3 = b2 - maxLoss;

            // Require at least 2 points of material loss (Exchange sacrifice or Minor piece
            // for pawn)
            // This prevents hanging a single pawn from generating a Brilliant evaluation.
            boolean isSacrifice = (b3 - b1) <= -2;

            // -- Great Move Punishment Detection --
            // If the opponent previously made a severe mistake/blunder (>= 120 CP Loss)
            // and we find the absolute best response (CP Loss = 0), we grant a Great Find.
            boolean isPunishment = false;
            if (i > 0) {
                int prevEvalBefore = evaluations.get(i - 1);
                int prevEvalAfter = evaluations.get(i);
                boolean prevIsWhite = sideToMove.get(i - 1);
                int prevCpLoss = prevIsWhite ? Math.max(0, prevEvalBefore - prevEvalAfter)
                        : Math.max(0, prevEvalAfter - prevEvalBefore);

                if (prevCpLoss >= 120 && cpLoss <= 15) {
                    isPunishment = true;
                }
            }

            String classification;
            if (inBook && openingBookService.isBookPosition(fenAfter)) {
                classification = "Book";
                cpLoss = 0; // Standardize 0 inaccuracy for known opening theory
            } else {
                inBook = false; // Deviated from theory, never return to book this game
                classification = classifyMove(cpLoss, isSacrifice, isPunishment);
            }

            // For sub-optimal moves, ask engine what the best move was from before-position
            String bestMoveUci = null;
            boolean isSubOptimal = classification.equals("Inaccuracy")
                    || classification.equals("Mistake")
                    || classification.equals("Miss")
                    || classification.equals("Blunder")
                    || classification.equals("Excellent")
                    || classification.equals("Good");
            if (isSubOptimal) {
                try {
                    StockfishService.EvalResult bestResult = stockfishService
                            .evaluateWithBestMove(fensToEvaluate.get(i), depth);
                    bestMoveUci = bestResult.bestMove();
                } catch (Exception ignored) {
                }
            }

            reviews.add(new MoveReview(
                    moveSans.get(i),
                    cpLoss,
                    evalAfter,
                    classification,
                    fenAfter,
                    bestMoveUci,
                    i < clockTimes.size() ? clockTimes.get(i) : null));
            totalCpLoss += cpLoss;
        }

        double avgCpLoss = !reviews.isEmpty() ? (double) totalCpLoss / reviews.size() : 0.0;
        double accuracy = Math.max(0, 100 - (avgCpLoss / 10));

        String whitePlayer = game.getWhitePlayer() != null ? game.getWhitePlayer().toString() : "White";
        String blackPlayer = game.getBlackPlayer() != null ? game.getBlackPlayer().toString() : "Black";

        // Extract Elo using regex for maximum reliability
        String whiteElo = "?";
        String blackElo = "?";

        Pattern whiteEloPattern = Pattern.compile("\\[WhiteElo\\s+\"(\\d+)\"\\]");
        Matcher whiteEloMatcher = whiteEloPattern.matcher(pgnContent);
        if (whiteEloMatcher.find())
            whiteElo = whiteEloMatcher.group(1);

        Pattern blackEloPattern = Pattern.compile("\\[BlackElo\\s+\"(\\d+)\"\\]");
        Matcher blackEloMatcher = blackEloPattern.matcher(pgnContent);
        if (blackEloMatcher.find())
            blackElo = blackEloMatcher.group(1);

        String timeControl = "10:00";
        Pattern timeControlPattern = Pattern.compile("\\[TimeControl\\s+\"(\\d+)\"\\]");
        Matcher timeControlMatcher = timeControlPattern.matcher(pgnContent);
        if (timeControlMatcher.find())
            timeControl = timeControlMatcher.group(1);

        return new AnalysisResponse(accuracy, reviews, whitePlayer, blackPlayer, whiteElo, blackElo, timeControl);
    }

    public int evaluatePosition(String fen, int depth) throws Exception {
        int rawEval = stockfishService.evaluatePosition(fen, depth);
        return fen.contains(" w ") ? rawEval : -rawEval;
    }

    private String classifyMove(int cpLoss, boolean isSacrifice, boolean isPunishment) {
        if (isSacrifice) {
            if (cpLoss <= 15)
                return "Brilliant";
            if (cpLoss <= 30)
                return "Great";
        }

        if (isPunishment && cpLoss <= 15) {
            return "Great";
        }

        if (cpLoss == 0)
            return "Best";
        if (cpLoss <= 15)
            return "Excellent";
        if (cpLoss <= 30)
            return "Good";
        if (cpLoss <= 60)
            return "Inaccuracy";
        if (cpLoss <= 120)
            return "Mistake";
        if (cpLoss <= 250)
            return "Miss";
        return "Blunder";
    }

    private int getPieceValue(com.github.bhlangonijr.chesslib.Piece piece) {
        if (piece == null || piece == com.github.bhlangonijr.chesslib.Piece.NONE)
            return 0;
        switch (piece.getPieceType()) {
            case PAWN:
                return 1;
            case KNIGHT:
            case BISHOP:
                return 3;
            case ROOK:
                return 5;
            case QUEEN:
                return 9;
            default:
                return 0;
        }
    }

    private int calculateMaterialBalance(Board board, boolean forWhite) {
        int whiteScore = 0;
        int blackScore = 0;
        for (com.github.bhlangonijr.chesslib.Square sq : com.github.bhlangonijr.chesslib.Square.values()) {
            if (sq == com.github.bhlangonijr.chesslib.Square.NONE)
                continue;
            com.github.bhlangonijr.chesslib.Piece p = board.getPiece(sq);
            if (p != com.github.bhlangonijr.chesslib.Piece.NONE) {
                int val = getPieceValue(p);
                if (p.getPieceSide() == com.github.bhlangonijr.chesslib.Side.WHITE)
                    whiteScore += val;
                else
                    blackScore += val;
            }
        }
        return forWhite ? (whiteScore - blackScore) : (blackScore - whiteScore);
    }

    private int calculateMaxMaterialLoss(Board boardAfter, boolean weAreWhite) {
        com.github.bhlangonijr.chesslib.Side ourSide = weAreWhite ? com.github.bhlangonijr.chesslib.Side.WHITE
                : com.github.bhlangonijr.chesslib.Side.BLACK;

        int maxLoss = 0;
        List<Move> captures = boardAfter.pseudoLegalCaptures();
        for (Move move : captures) {
            com.github.bhlangonijr.chesslib.Piece capturedPiece = boardAfter.getPiece(move.getTo());
            com.github.bhlangonijr.chesslib.Piece capturingPiece = boardAfter.getPiece(move.getFrom());

            if (capturedPiece != null && capturedPiece != com.github.bhlangonijr.chesslib.Piece.NONE
                    && capturedPiece.getPieceSide() == ourSide) {

                int capturedValue = getPieceValue(capturedPiece);
                int capturingValue = getPieceValue(capturingPiece);

                boolean isProtected = boardAfter.squareAttackedBy(move.getTo(), ourSide) != 0L;

                if (!isProtected) {
                    maxLoss = Math.max(maxLoss, capturedValue);
                } else {
                    if (capturedValue > capturingValue) {
                        maxLoss = Math.max(maxLoss, capturedValue - capturingValue);
                    }
                }
            }
        }
        return maxLoss;
    }
}