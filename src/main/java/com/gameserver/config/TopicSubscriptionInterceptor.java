package com.gameserver.config;

import com.gameserver.model.GameSession;
import com.gameserver.repository.GameSessionRepository;
import com.gameserver.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound ChannelInterceptor securing STOMP WebSocket traffic.
 * 1. On CONNECT: Extracts & validates JWT from STOMP headers, setting the authenticated Principal.
 * 2. On SUBSCRIBE: Enforces that the user is an authorized participant in the target GameSession.
 */
@Component
public class TopicSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TopicSubscriptionInterceptor.class);

    private static final Pattern GAME_TOPIC_PATTERN = Pattern.compile("^/topic/game/([^/]+)$");

    private final JwtService jwtService;
    private final GameSessionRepository gameSessionRepository;

    public TopicSubscriptionInterceptor(JwtService jwtService, GameSessionRepository gameSessionRepository) {
        this.jwtService = jwtService;
        this.gameSessionRepository = gameSessionRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            handleSubscribe(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);

        if (token == null) {
            log.warn("STOMP CONNECT rejected: Missing Authorization header");
            throw new AccessDeniedException("Missing JWT authentication token");
        }

        if (!jwtService.validateToken(token)) {
            log.warn("STOMP CONNECT rejected: Invalid or expired JWT token");
            throw new AccessDeniedException("Invalid or expired JWT token");
        }

        String username = jwtService.extractUsername(token);
        Long userId = jwtService.extractUserId(token);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                userId,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        accessor.setUser(authentication);
        log.info("STOMP session authenticated for user: {} (id: {})", username, userId);
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        Matcher matcher = GAME_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            // Allow subscriptions to other non-game topics (e.g., notifications) if any
            return;
        }

        String gameId = matcher.group(1);
        Principal principal = accessor.getUser();

        if (principal == null || principal.getName() == null) {
            log.warn("STOMP SUBSCRIBE rejected on {}: Unauthenticated connection", destination);
            throw new AccessDeniedException("Unauthenticated users cannot subscribe to game topic");
        }

        String username = principal.getName();

        GameSession session = gameSessionRepository.findByGameId(gameId)
                .orElse(null);

        if (session == null) {
            log.warn("STOMP SUBSCRIBE rejected on {}: Game session not found", destination);
            throw new AccessDeniedException("Game session '" + gameId + "' does not exist");
        }

        if (!session.isParticipant(username)) {
            log.warn("STOMP SUBSCRIBE rejected on {}: User '{}' is not a registered participant", destination, username);
            throw new AccessDeniedException("User '" + username + "' is not a registered participant of game '" + gameId + "'");
        }

        log.info("STOMP SUBSCRIBE authorized: user '{}' subscribed to game '{}'", username, gameId);
    }

    private String extractToken(StompHeaderAccessor accessor) {
        // Check standard Authorization native header
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null) {
            authHeader = accessor.getFirstNativeHeader("authorization");
        }
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        // Fallback to 'token' native header
        String tokenHeader = accessor.getFirstNativeHeader("token");
        if (tokenHeader != null && !tokenHeader.trim().isEmpty()) {
            return tokenHeader.trim();
        }

        return null;
    }
}
