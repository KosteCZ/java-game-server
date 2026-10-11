package com.gameserver.controller;

import com.gameserver.dto.GameAction;
import com.gameserver.dto.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * Controller responsible for processing real-time game moves via STOMP WebSockets
 * and synchronizing match state.
 */
@Controller
public class GameController {

    private static final Logger log = LoggerFactory.getLogger(GameController.class);

    /**
     * Handles player move actions dispatched to destination /app/game/{gameId}/move.
     * The verified new state is automatically broadcast to subscribers on /topic/game/{gameId}.
     *
     * @param gameId Target game/room identifier
     * @param action Incoming action payload containing playerId and move data
     * @return Updated GameState broadcast to all connected players in the match
     */
    @MessageMapping("/game/{gameId}/move")
    @SendTo("/topic/game/{gameId}")
    public GameState handleMove(@DestinationVariable String gameId, @Payload GameAction action) {
        log.info("Received move for gameId: {} from player: {}", gameId, action.playerId());

        /*
         * =========================================================================================
         * [STATE VERIFICATION & SYNC PLACEHOLDER]
         *
         * 1. VERIFY GAME STATE:
         *    - Validate player identity, turn order, and move legality against current room rules.
         *    - Apply move mutations to the in-memory or cached room board state.
         *
         * 2. SUPABASE WRITE-BACK / STATE SYNCHRONIZATION:
         *    - Persist the authoritative game state snapshot to the external Supabase PostgreSQL DB:
         *        e.g., gameSessionRepository.save(new GameSessionEntity(gameId, currentState, Instant.now()));
         *    - This prevents losing ongoing player sessions during container spin-down or server
         *      cold starts on Koyeb Free Tier when inactive instances are recycled.
         * =========================================================================================
         */

        String currentStatus = "IN_PROGRESS";
        return GameState.of(gameId, currentStatus, action.playerId(), action.moveData());
    }

    /**
     * Lightweight health check endpoint for Koyeb deployment health monitoring.
     */
    @GetMapping("/api/health")
    @ResponseBody
    public Map<String, Object> healthCheck() {
        return Map.of(
                "status", "UP",
                "service", "Java Game Server",
                "virtualThreads", true
        );
    }
}
