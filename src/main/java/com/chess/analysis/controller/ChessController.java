package com.chess.analysis.controller;

import com.chess.analysis.model.AnalysisResponse;
import com.chess.analysis.service.ChessAnalysisService;
import com.chess.analysis.service.StockfishService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@CrossOrigin
public class ChessController {

    private final ChessAnalysisService analysisService;
    private final StockfishService stockfishService;

    public ChessController(ChessAnalysisService analysisService, StockfishService stockfishService) {
        this.analysisService = analysisService;
        this.stockfishService = stockfishService;
    }

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @PostMapping("/analyze")
    public String analyze(@RequestParam("pgn") String pgn, Model model) throws Exception {

        AnalysisResponse response = analysisService.analyzeGame(pgn);

        model.addAttribute("accuracy", response.getAccuracy());
        model.addAttribute("whitePlayer", response.getWhitePlayer());
        model.addAttribute("blackPlayer", response.getBlackPlayer());
        model.addAttribute("whiteElo", response.getWhiteElo());
        model.addAttribute("blackElo", response.getBlackElo());
        model.addAttribute("moves", response.getMoves());

        return "result";
    }

    @PostMapping("/api/evaluate")
    @ResponseBody
    public String evaluatePosition(@RequestBody java.util.Map<String, String> payload) {
        try {
            String fen = payload.get("fen");
            int eval = analysisService.evaluatePosition(fen, 10);
            return "{\"evaluation\": " + eval + "}";
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"Evaluation failed\"}";
        }
    }

    @PostMapping("/api/bestmove")
    @ResponseBody
    public String getBestMove(@RequestBody java.util.Map<String, String> payload) {
        try {
            String fen = payload.get("fen");
            StockfishService.EvalResult result = stockfishService.evaluateWithBestMove(fen, 10);
            return "{\"evaluation\": " + result.evaluation() + ", \"bestMove\": \""
                    + (result.bestMove() != null ? result.bestMove() : "") + "\"}";
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"Best move lookup failed\"}";
        }
    }
}
