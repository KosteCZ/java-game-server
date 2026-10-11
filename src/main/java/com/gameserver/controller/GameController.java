package com.gameserver.controller;

import com.gameserver.dto.GameAction;
import com.gameserver.dto.GameState;
import com.gameserver.model.GameSession;
import com.gameserver.repository.GameSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

/**
 * Controller responsible for processing real-time game moves via STOMP WebSockets
 * and synchronizing match state with Supabase.
 */
@Controller
public class GameController {

    private static final Logger log = LoggerFactory.getLogger(GameController.class);

    private final GameSessionRepository gameSessionRepository;

    public GameController(GameSessionRepository gameSessionRepository) {
        this.gameSessionRepository = gameSessionRepository;
    }

    /**
     * Handles player move actions dispatched to destination /app/game/{gameId}/move.
     * Validates that the authenticated Principal matches the playerId in the action,
     * verifies participation, and writes state back to Supabase.
     *
     * @param gameId Target game/room identifier
     * @param action Incoming action payload containing playerId and move data
     * @param principal Authenticated principal from the WebSocket session
     * @return Updated GameState broadcast to all connected players in the match
     */
    @MessageMapping("/game/{gameId}/move")
    @SendTo("/topic/game/{gameId}")
    public GameState handleMove(@DestinationVariable String gameId,
                                @Payload GameAction action,
                                Principal principal) {
        log.info("Received move for gameId: {} from player: {}", gameId, action.playerId());

        // 1. Verify caller authentication
        if (principal == null || principal.getName() == null) {
            log.warn("Unauthenticated move attempt on gameId: {}", gameId);
            throw new AccessDeniedException("Unauthenticated user cannot execute game moves");
        }

        String authenticatedUser = principal.getName();

        // 2. Validate that the authenticated Principal username matches the action's playerId
        if (!authenticatedUser.equals(action.playerId())) {
            log.warn("Unauthorized move spoofing attempt: authenticatedUser='{}' tried to move as playerId='{}'",
                    authenticatedUser, action.playerId());
            throw new AccessDeniedException("Unauthorized move: authenticated user '" +
                    authenticatedUser + "' does not match player '" + action.playerId() + "'");
        }

        // 3. Verify game state and synchronize with Supabase PostgreSQL
        GameSession session = gameSessionRepository.findByGameId(gameId)
                .orElseGet(() -> {
                    GameSession newSession = new GameSession(gameId);
                    newSession.addParticipant(authenticatedUser);
                    return newSession;
                });

        if (!session.isParticipant(authenticatedUser)) {
            throw new AccessDeniedException("User '" + authenticatedUser + "' is not a participant in game '" + gameId + "'");
        }

        session.setLastMoveBy(authenticatedUser);
        session.setUpdatedAt(Instant.now());
        gameSessionRepository.save(session);

        String currentStatus = session.getStatus();
        return GameState.of(gameId, currentStatus, action.playerId(), action.moveData());
    }

    /**
     * REST endpoint allowing an authenticated player to register/join a game room session.
     */
    @PostMapping("/api/game/{gameId}/join")
    @ResponseBody
    public ResponseEntity<?> joinGame(@PathVariable String gameId,
                                      @RequestBody(required = false) Map<String, String> body,
                                      Principal principal) {
        String username = principal != null ? principal.getName() : (body != null ? body.get("username") : null);

        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required to join a game room"));
        }

        GameSession session = gameSessionRepository.findByGameId(gameId)
                .orElseGet(() -> new GameSession(gameId));

        session.addParticipant(username.trim());
        session.setUpdatedAt(Instant.now());
        session = gameSessionRepository.save(session);

        log.info("User '{}' joined game room '{}'. Participants: {}", username, gameId, session.getParticipants());

        return ResponseEntity.ok(Map.of(
                "gameId", session.getGameId(),
                "status", session.getStatus(),
                "participants", session.getParticipants()
        ));
    }

    /**
     * Lightweight health check endpoint for deployment health monitoring.
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
