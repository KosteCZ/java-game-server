package com.gameserver.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * Lightweight immutable representation of the verified state of a game match.
 *
 * @param gameId     Unique identifier of the match session
 * @param status     Current game status (e.g., IN_PROGRESS, COMPLETED, WAITING)
 * @param lastMoveBy Player ID who executed the last verified move
 * @param moveData   The payload of the move applied
 * @param updatedAt  Unix timestamp (epoch millis) when state was generated
 */
public record GameState(
        String gameId,
        String status,
        String lastMoveBy,
        Object moveData,
        long updatedAt
) implements Serializable {

    public static GameState of(String gameId, String status, String lastMoveBy, Object moveData) {
        return new GameState(gameId, status, lastMoveBy, moveData, Instant.now().toEpochMilli());
    }
}
