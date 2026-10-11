package com.gameserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for high-performance STOMP messaging broker.
 * Optimized for low-memory overhead with simple in-memory broker and virtual threads.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory message broker carrying messages back to connected clients on /topic
        registry.enableSimpleBroker("/topic");

        // Prefix for messages bound for @MessageMapping handler methods
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Register raw STOMP endpoint for game clients (supports cross-origin via allowedOriginPatterns)
        registry.addEndpoint("/ws-game")
                .setAllowedOriginPatterns("*");

        // Optional SockJS fallback support endpoint for environments where pure WebSockets are blocked
        registry.addEndpoint("/ws-game")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
