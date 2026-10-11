package com.gameserver.dto;

public record LoginRequest(
        String username,
        String password
) {}
