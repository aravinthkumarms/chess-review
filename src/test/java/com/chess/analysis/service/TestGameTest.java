package com.chess.analysis.service;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.game.Game;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

public class TestGameTest {

    @Test
    public void testPgnParsing() throws Exception {
        String pgn = "1. e4 Nf6 2. Nc3 e6 3. Qf3 Ke7 4. Nh3 Ke8 5. e5 Ng8 6. Ng5 Qxg5 7. d3 Qxe5+ 8. Be2 Nc6 9. Bf4 Qf5 10. Nb5 Qxb5 11. d4 Qb4+ 12. c3 Qxb2 13. Rd1 e5 14. Bc1 Qxa2 15. Rd2 Qb1 16. Rd1 e4 17. Qe3 Nf6 18. Bd2 Qa2 19. g4 Nxg4 20. Bxg4 g6 21. Qxe4+ Kd8 22. O-O f5 23. Qe1 fxg4 24. Bg5+ Be7 25. Bxe7+ Nxe7 26. Rd2 Qd5 27. Re2 b5 28. Rxe7 Bb7 29. Re2 Qg2# 0-1";

        File temp = File.createTempFile("testgame", ".pgn");
        Files.writeString(temp.toPath(), pgn);

        PgnHolder holder = new PgnHolder(temp.getAbsolutePath());
        holder.loadPgn();
        Game game = holder.getGames().get(0);

        System.out.println("Loaded game with " + game.getHalfMoves().size() + " half-moves.");

        Board board = new Board();
        int moveNum = 1;
        boolean isWhite = true;
        for (Move move : game.getHalfMoves()) {
            Board tempBoard = board.clone();
            int b1 = calculateMaterialBalance(tempBoard, isWhite);

            board.doMove(move);

            tempBoard = board.clone();
            int b2 = calculateMaterialBalance(tempBoard, isWhite);
            int maxLoss = calculateMaxMaterialLoss(tempBoard, isWhite);
            int b3 = b2 - maxLoss;

            boolean isSacrifice = (b3 - b1) <= -1;

            System.out.println("Move " + moveNum + (isWhite ? " (W)" : " (B)") + " " + move.toString() +
                    " | B1: " + b1 + " | B2: " + b2 + " | maxLoss: " + maxLoss + " | B3: " + b3 +
                    " | b3-b1: " + (b3 - b1) + " | Sac? " + isSacrifice);

            if (!isWhite)
                moveNum++;
            isWhite = !isWhite;
        }
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
        java.util.List<Move> captures = boardAfter.pseudoLegalCaptures();
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
