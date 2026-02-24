package com.chess.analysis.model;

import java.util.List;

public class AnalysisResponse {

    private double accuracy;
    private List<MoveReview> moves;
    private String whitePlayer;
    private String blackPlayer;
    private String whiteElo;
    private String blackElo;
    private String timeControl;

    public AnalysisResponse(double accuracy, List<MoveReview> moves, String whitePlayer, String blackPlayer,
            String whiteElo, String blackElo, String timeControl) {
        this.accuracy = accuracy;
        this.moves = moves;
        this.whitePlayer = whitePlayer;
        this.blackPlayer = blackPlayer;
        this.whiteElo = whiteElo;
        this.blackElo = blackElo;
        this.timeControl = timeControl;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public List<MoveReview> getMoves() {
        return moves;
    }

    public String getWhitePlayer() {
        return whitePlayer;
    }

    public String getBlackPlayer() {
        return blackPlayer;
    }

    public String getWhiteElo() {
        return whiteElo;
    }

    public String getBlackElo() {
        return blackElo;
    }

    public String getTimeControl() {
        return timeControl;
    }
}
