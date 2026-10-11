package com.gameserver.dto;

public record RegisterRequest(
        String username,
        String password
) {}
