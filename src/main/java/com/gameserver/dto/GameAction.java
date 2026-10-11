package com.gameserver.dto;

import java.io.Serializable;

/**
 * Lightweight immutable representation of a move action submitted by a player.
 *
 * @param playerId Unique identifier of the initiating player
 * @param moveData Arbitrary action payload (e.g., coordinates, card play, movement vector)
 */
public record GameAction(
        String playerId,
        Object moveData
) implements Serializable {
}
