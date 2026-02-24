package com.chess.analysis.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@Service
public class StockfishService {

    /**
     * Packages the centipawn evaluation plus the engine's recommended best move
     * (UCI).
     */
    public record EvalResult(int evaluation, String bestMove) {
    }

    private BlockingQueue<StockfishEngine> enginePool;
    private final int poolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    @PostConstruct
    public void initPool() throws IOException {
        enginePool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            enginePool.offer(new StockfishEngine());
        }
    }

    @PreDestroy
    public void destroyPool() {
        if (enginePool != null) {
            for (StockfishEngine engine : enginePool) {
                engine.close();
            }
        }
    }

    /**
     * Returns only the centipawn evaluation — used by the live /api/evaluate
     * endpoint.
     */
    public int evaluatePosition(String fen, int depth) throws IOException, InterruptedException {
        StockfishEngine engine = enginePool.take();
        try {
            return engine.evaluatePosition(fen, depth);
        } finally {
            enginePool.offer(engine);
        }
    }

    /**
     * Returns both the centipawn evaluation AND the engine's best move (UCI) — used
     * by batch analysis.
     */
    public EvalResult evaluateWithBestMove(String fen, int depth) throws IOException, InterruptedException {
        StockfishEngine engine = enginePool.take();
        try {
            return engine.evaluateWithBestMove(fen, depth);
        } finally {
            enginePool.offer(engine);
        }
    }

    private class StockfishEngine {

        private Process engineProcess;
        private BufferedReader reader;
        private BufferedWriter writer;

        public StockfishEngine() throws IOException {
            startEngine();
        }

        private void startEngine() throws IOException {
            File tempExe = File.createTempFile("stockfish_instance", ".exe");
            tempExe.deleteOnExit();

            try (InputStream is = getClass().getResourceAsStream("/stockfish.exe")) {
                if (is == null) {
                    throw new FileNotFoundException("stockfish.exe not found in resources directory.");
                }
                java.nio.file.Files.copy(is, tempExe.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            engineProcess = new ProcessBuilder(tempExe.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();

            reader = new BufferedReader(new InputStreamReader(engineProcess.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(engineProcess.getOutputStream()));

            sendCommand("uci");
            waitFor("uciok");
        }

        private void sendCommand(String command) throws IOException {
            writer.write(command + "\n");
            writer.flush();
        }

        private void waitFor(String text) throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(text))
                    break;
            }
        }

        public int evaluatePosition(String fen, int depth) throws IOException {
            sendCommand("position fen " + fen);
            sendCommand("go depth " + depth);

            String line;
            int evaluation = 0;

            while ((line = reader.readLine()) != null) {

                if (line.contains("score cp") || line.contains("score mate")) {
                    String[] parts = line.split(" ");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals("score")) {
                            if (parts[i + 1].equals("cp")) {
                                evaluation = Integer.parseInt(parts[i + 2]);
                            } else if (parts[i + 1].equals("mate")) {
                                int mateIn = Integer.parseInt(parts[i + 2]);
                                evaluation = mateIn > 0 ? 10000 - mateIn : -10000 - mateIn;
                            }
                        }
                    }
                }

                if (line.startsWith("bestmove")) {
                    break;
                }
            }

            return evaluation;
        }

        public EvalResult evaluateWithBestMove(String fen, int depth) throws IOException {
            sendCommand("position fen " + fen);
            sendCommand("go depth " + depth);

            String line;
            int evaluation = 0;
            String bestMove = null;

            while ((line = reader.readLine()) != null) {

                if (line.contains("score cp") || line.contains("score mate")) {
                    String[] parts = line.split(" ");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals("score")) {
                            if (parts[i + 1].equals("cp")) {
                                evaluation = Integer.parseInt(parts[i + 2]);
                            } else if (parts[i + 1].equals("mate")) {
                                int mateIn = Integer.parseInt(parts[i + 2]);
                                evaluation = mateIn > 0 ? 10000 - mateIn : -10000 - mateIn;
                            }
                        }
                    }
                }

                if (line.startsWith("bestmove")) {
                    String[] tokens = line.split(" ");
                    if (tokens.length >= 2 && !tokens[1].equals("(none)")) {
                        bestMove = tokens[1]; // e.g. "e2e4"
                    }
                    break;
                }
            }

            return new EvalResult(evaluation, bestMove);
        }

        public void close() {
            try {
                if (writer != null) {
                    sendCommand("quit");
                    writer.close();
                }
                if (reader != null) {
                    reader.close();
                }
                if (engineProcess != null) {
                    engineProcess.destroy();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}