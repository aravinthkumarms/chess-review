package com.chess.analysis.service;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.game.Game;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

@Service
public class OpeningBookService {

    private static final Logger log = LoggerFactory.getLogger(OpeningBookService.class);
    // HashSet to store normalized FENs (position + active color + castling + en
    // passant target)
    private final Set<String> bookPositions = new HashSet<>();

    @PostConstruct
    public void loadOpeningBook() {
        try {
            log.info("Starting Lichess Opening Book Initialization...");
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:openings/*.tsv");

            StringBuilder multiGamePgn = new StringBuilder();

            log.info("Found {} TSV opening databases.", resources.length);
            for (Resource resource : resources) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    boolean isFirstLine = true;
                    while ((line = reader.readLine()) != null) {
                        if (isFirstLine) {
                            isFirstLine = false; // skip header
                            continue;
                        }
                        String[] parts = line.split("\t");
                        if (parts.length >= 3) {
                            String pgnSequence = parts[2].trim();
                            // Wrap the sequence into a standard PGN game text block for PgnHolder
                            multiGamePgn.append("[Event \"?\"]\n\n").append(pgnSequence).append(" *\n\n");
                        }
                    }
                }
            }

            // Write the mega-PGN into a temp file to utilize the fast C-like PgnHolder
            // library
            File tempPgnDb = File.createTempFile("eco_database", ".pgn");
            Files.writeString(tempPgnDb.toPath(), multiGamePgn.toString());

            PgnHolder holder = new PgnHolder(tempPgnDb.getAbsolutePath());
            holder.loadPgn();

            for (Game game : holder.getGames()) {
                Board board = new Board(); // Start at standard position
                // Store starting position as theory
                bookPositions.add(normalizeFen(board.getFen()));
                // Play out each theory sequence and store every resulting position
                for (Move move : game.getHalfMoves()) {
                    board.doMove(move);
                    bookPositions.add(normalizeFen(board.getFen()));
                }
            }

            tempPgnDb.delete();
            log.info("Loaded {} unique theoretical board positions into the Book.", bookPositions.size());
        } catch (Exception e) {
            log.error("Failed to load Lichess ECO TSV files: {}", e.getMessage());
        }
    }

    public boolean isBookPosition(String fen) {
        return bookPositions.contains(normalizeFen(fen));
    }

    /**
     * Standardizes a FEN so identical positions with different half-move clocks
     * math natively.
     * We retain Position, Active Color, Castling, and En Passant rights.
     */
    private String normalizeFen(String fullFen) {
        String[] parts = fullFen.split(" ");
        if (parts.length >= 4) {
            return String.join(" ", parts[0], parts[1], parts[2], parts[3]);
        }
        return fullFen;
    }
}
