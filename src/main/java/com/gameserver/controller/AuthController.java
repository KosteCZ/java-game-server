package com.gameserver.controller;

import com.gameserver.dto.AuthResponse;
import com.gameserver.dto.LoginRequest;
import com.gameserver.dto.RegisterRequest;
import com.gameserver.model.User;
import com.gameserver.repository.UserRepository;
import com.gameserver.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller handling user registration and JWT login.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request.username() == null || request.username().trim().isEmpty() ||
            request.password() == null || request.password().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required."));
        }

        String username = request.username().trim();
        if (userRepository.existsByUsername(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Username '" + username + "' is already taken."));
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(username, passwordHash);
        user = userRepository.save(user);

        log.info("Registered new user: {} (id: {})", user.getUsername(), user.getId());

        String token = jwtService.generateToken(user.getUsername(), user.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthResponse.of(token, user.getUsername(), user.getId()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required."));
        }

        String username = request.username().trim();
        User user = userRepository.findByUsername(username)
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password."));
        }

        log.info("User logged in successfully: {} (id: {})", user.getUsername(), user.getId());

        String token = jwtService.generateToken(user.getUsername(), user.getId());
        return ResponseEntity.ok(AuthResponse.of(token, user.getUsername(), user.getId()));
    }
}
