package com.gameserver.dto;

public record AuthResponse(
        String token,
        String username,
        Long userId,
        String tokenType
) {
    public static AuthResponse of(String token, String username, Long userId) {
        return new AuthResponse(token, username, userId, "Bearer");
    }
}
