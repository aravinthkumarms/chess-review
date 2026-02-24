package com.chess.analysis.model;

public class MoveReview {

    private String move; // SAN move
    private int centipawnLoss;
    private int evaluation;
    private String classification;
    private String fen;
    private String bestMove; // UCI best move suggested by engine (null when not needed)
    private String clockTime; // Remaining clock time from [%clk] annotation, e.g. "4:57"

    public MoveReview(String move, int centipawnLoss,
            int evaluation, String classification, String fen) {
        this.move = move;
        this.centipawnLoss = centipawnLoss;
        this.evaluation = evaluation;
        this.classification = classification;
        this.fen = fen;
    }

    public MoveReview(String move, int centipawnLoss,
            int evaluation, String classification, String fen, String bestMove) {
        this(move, centipawnLoss, evaluation, classification, fen);
        this.bestMove = bestMove;
    }

    public MoveReview(String move, int centipawnLoss,
            int evaluation, String classification, String fen, String bestMove, String clockTime) {
        this(move, centipawnLoss, evaluation, classification, fen, bestMove);
        this.clockTime = clockTime;
    }

    public String getMove() {
        return move;
    }

    public int getCentipawnLoss() {
        return centipawnLoss;
    }

    public int getEvaluation() {
        return evaluation;
    }

    public String getClassification() {
        return classification;
    }

    public String getFen() {
        return fen;
    }

    public String getBestMove() {
        return bestMove;
    }

    public String getClockTime() {
        return clockTime;
    }
}