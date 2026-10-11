package com.gameserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for the multiplayer game server.
 * Currently permits all WebSocket handshake and REST endpoints for MVP development,
 * while providing the skeleton and hooks for production JWT authentication.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF as communication is stateless and STOMP-based
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // Stateless session management: low memory consumption, ideal for 512MB RAM
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Permit WebSocket STOMP endpoint handshakes (native and SockJS fallbacks)
                        .requestMatchers("/ws-game/**").permitAll()
                        // Permit public game API & health monitoring endpoints
                        .requestMatchers("/api/**").permitAll()
                        // Allow root, static UI assets, and error pages
                        .requestMatchers("/", "/index.html", "/error", "/favicon.ico", "/*.html", "/*.css", "/*.js").permitAll()
                        /*
                         * =========================================================================
                         * [FUTURE JWT TOKEN VALIDATION SKELETON]
                         *
                         * When integrating Supabase Auth / custom JWT validation:
                         * 1. Register a JwtAuthenticationFilter here:
                         *    .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                         * 2. Protect authenticated REST endpoints:
                         *    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                         *    .requestMatchers("/api/secure/**").authenticated()
                         * 3. For STOMP WebSockets:
                         *    HTTP filters only protect the initial handshake (/ws-game).
                         *    To validate JWT on incoming WebSocket frames, implement a
                         *    ChannelInterceptor in WebSocketConfig on 'preSend' checking
                         *    StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)
                         *    for StompCommand.CONNECT with the 'Authorization: Bearer <token>' header.
                         * =========================================================================
                         */
                        .anyRequest().authenticated()
                );

        return http.build();
    }

    /**
     * In-memory UserDetailsService placeholder for MVP setup without basic-auth popups.
     */
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService userDetailsService() {
        return new org.springframework.security.provisioning.InMemoryUserDetailsManager();
    }

    /**
     * Cross-Origin Resource Sharing (CORS) configuration for browser-based game clients.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
