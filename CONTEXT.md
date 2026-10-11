# Project Context & Architecture Memory (`CONTEXT.md`)

This document serves as the **persistent source of truth and architectural memory** for any AI agent or software engineer working on this codebase.

---

## 1. Project Overview

### 1.1 Purpose
The **Java Game Server** is a high-concurrency, lightweight multiplayer game server built with **Java 21 LTS** and **Spring Boot 3.3.5**. It provides real-time bi-directional messaging for multiplayer matches using the **STOMP protocol over WebSockets**, coupled with **BCrypt/JWT authentication** and persistent session storage in an external **Supabase PostgreSQL** database.

### 1.2 Target Deployment Constraints
* **Platform**: Google Cloud Run (and Koyeb Nano Free Tier).
* **RAM Ceiling**: **512 MB maximum container memory**. Every component, thread pool, and garbage collection strategy is tightly tuned to operate safely under this threshold.
* **Instance Ceiling**: `--max-instances=1` (Strict cost protection ceiling: prevents autoscaling charges, enforces a single active node, and protects against billing shocks/DDoS attacks).
* **Concurrency**: `--concurrency=80` (Up to 80 concurrent connections per single container instance).
* **Region**: `europe-west3` (Frankfurt, Germany) to match European low-latency requirements.

---

## 2. Architectural Decisions & Low-Memory Optimizations

```
+-----------------------------------------------------------------------------------------+
|                                     Client (Browser)                                    |
+-----------------------------------------------------------------------------------------+
                    |                                                ^
     HTTP REST      | POST /api/auth/login                           | STOMP WebSocket
     (JWT Issuance) | POST /api/game/{id}/join                       | /topic/game/{gameId}
                    v                                                |
+-----------------------------------------------------------------------------------------+
|                                 Spring Boot 3.3.5 Runtime                               |
|                                                                                         |
|  [ Tomcat Worker Pool: max 20, min 2 ]    [ Virtual Threads: Enabled (Project Loom) ]   |
|  [ JMX: Disabled                     ]    [ Garbage Collector: SerialGC             ]   |
|                                                                                         |
|  +------------------------+                     +------------------------------------+  |
|  | JwtAuthenticationFilter|                     |   TopicSubscriptionInterceptor     |  |
|  | (REST Bearer Token)    |                     |   (STOMP CONNECT & SUBSCRIBE auth) |  |
|  +------------------------+                     +------------------------------------+  |
|               |                                                   |                     |
|               v                                                   v                     |
|  +------------------------+                     +------------------------------------+  |
|  |     AuthController     |                     |     GameController (@MessageMapping|  |
|  +------------------------+                     +------------------------------------+  |
+-----------------------------------------------------------------------------------------+
                    |                                                |
                    +-----------------------+------------------------+
                                            |
                                            | HikariCP (max 5, min 1)
                                            v
+-----------------------------------------------------------------------------------------+
|                             External Supabase PostgreSQL                                |
|                        [ users ]      [ game_sessions ]                                 |
+-----------------------------------------------------------------------------------------+
```

### 2.1 Threading & Concurrency (Project Loom)
* **Virtual Threads Enabled**: Configured via `spring.threads.virtual.enabled=true`. Instead of allocating a 1 MB OS stack per thread for blocking network operations, Spring Boot runs request handling and WebSocket tasks on lightweight Java 21 Virtual Threads.
* **Bounded Tomcat Thread Pool**: Embedded Tomcat is bounded tightly:
  * `server.tomcat.threads.max: 20`
  * `server.tomcat.threads.min-spare: 2`
  * `server.tomcat.max-connections: 200`
  * `server.tomcat.accept-count: 20`
  This prevents runaway thread creation during traffic surges.

### 2.2 JVM & Memory Tuning
Configured in `Dockerfile` via `JAVA_TOOL_OPTIONS`:
* **`-XX:+UseSerialGC`**: Avoids the large footprint and multi-thread data structures of G1GC, saving ~20–40 MB of RAM on 1-vCPU/low-memory containers.
* **`-XX:MaxRAMPercentage=70.0`**: Allocates ~350 MB to JVM heap, leaving ~160 MB headroom for thread stacks, Metaspace, DirectByteBuffers (NIO buffers for WebSockets), and OS operations.
* **`-XX:+ExitOnOutOfMemoryError`**: Fails fast to ensure the orchestrator (Cloud Run / Koyeb) immediately restarts the container instead of locking up.
* **`spring.jmx.enabled: false`**: Completely disables JMX MBeans, eliminating unnecessary heap and Metaspace overhead.

### 2.3 Database Connection Pool (HikariCP)
Supabase Free Tier has strict connection limits (direct or pooler ports 5432/6543):
* `maximum-pool-size: 5`
* `minimum-idle: 1`
* `idle-timeout: 300000` (5 minutes)
* `connection-timeout: 20000` (20 seconds)
* `initialization-fail-timeout: -1` (Prevents application boot failures if the external Supabase instance is sleeping or experiencing a cold start).
* `spring.jpa.open-in-view: false` (Critical: avoids holding open JDBC database connections throughout long-lived WebSocket connections).

### 2.4 Scale-to-Zero Protection (Stateless Database Writeback)
Because serverless container platforms (Cloud Run / Koyeb) scale inactive instances down to zero:
* The server stores **no long-term authoritative session state in local memory**.
* In `GameController`, every verified move triggers a writeback directly to the `game_sessions` table in Supabase.
* When a cold start occurs, subsequent requests or reconnecting players reconstruct the game state from Supabase without data loss.

---

## 3. Data Model & Schema Registry

### 3.1 Database Tables (PostgreSQL / Supabase)

#### Table: `users`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | `PRIMARY KEY GENERATED ALWAYS AS IDENTITY` | Unique user identifier. |
| `username` | `VARCHAR(50)` | `NOT NULL, UNIQUE` | Unique player handle. |
| `password_hash` | `VARCHAR(255)`| `NOT NULL` | BCrypt password hash. |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp of registration. |

#### Table: `game_sessions`
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `BIGINT` | `PRIMARY KEY GENERATED ALWAYS AS IDENTITY` | Internal database surrogate key. |
| `game_id` | `VARCHAR(100)`| `NOT NULL, UNIQUE` | Room identifier (e.g., `room-101`). |
| `status` | `VARCHAR(30)` | `NOT NULL` | Match state (`WAITING`, `IN_PROGRESS`, `FINISHED`). |
| `last_move_by` | `VARCHAR(50)` | `NULLABLE` | Username of the last player who moved. |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp of latest state change. |

#### Table: `game_session_participants` (`@ElementCollection`)
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `session_id` | `BIGINT` | `FOREIGN KEY REFERENCES game_sessions(id)` | Parent game session. |
| `username` | `VARCHAR(50)` | `NOT NULL` | Authorized player username in this room. |

---

## 4. API & Messaging Registry

### 4.1 REST API Endpoints

| Method | Endpoint | Auth | Request Body | Response Body | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Public | `{"username": "...", "password": "..."}` | `{"token": "...", "username": "...", "userId": 1, "tokenType": "Bearer"}` | Hashes password with BCrypt, saves User, returns signed JWT. |
| `POST` | `/api/auth/login` | Public | `{"username": "...", "password": "..."}` | `{"token": "...", "username": "...", "userId": 1, "tokenType": "Bearer"}` | Verifies password with BCrypt, returns signed JWT. |
| `POST` | `/api/game/{gameId}/join` | Public / Bearer | `{"username": "..."}` (or from JWT Principal) | `{"gameId": "...", "status": "...", "participants": [...]}` | Registers user as an authorized participant in `game_sessions`. |
| `GET` | `/api/health` | Public | None | `{"status": "UP", "service": "...", "virtualThreads": true}` | Deployment liveness and health probe endpoint. |
| `GET` | `/` or `/index.html` | Public | None | HTML Web Client | Single-page UI for live WebSocket testing. |

### 4.2 WebSocket STOMP Destinations

* **Handshake Endpoint**: `/ws-game` (Registered with `allowedOriginPatterns("*")` and `.withSockJS()`).
* **Application Inbound Destination Prefix**: `/app`
* **Broker Outbound Destination Prefix**: `/topic`

#### Inbound Routes (Client -> Server)
* **Destination**: `/app/game/{gameId}/move`
* **Controller**: `GameController.handleMove`
* **Payload (`GameAction` record)**:
  ```json
  {
    "playerId": "player1",
    "moveData": {
      "index": 4,
      "row": 1,
      "col": 1
    }
  }
  ```

#### Outbound Routes (Server -> Client Broadcast)
* **Destination**: `/topic/game/{gameId}`
* **Subscribers**: Registered room participants.
* **Payload (`GameState` record)**:
  ```json
  {
    "gameId": "room-101",
    "status": "IN_PROGRESS",
    "lastMoveBy": "player1",
    "moveData": {
      "index": 4,
      "row": 1,
      "col": 1
    },
    "updatedAt": 1776023245000
  }
  ```

---

## 5. Security Model & Interceptors

```
STOMP Client                   TopicSubscriptionInterceptor              Target Handler
     |                                      |                                  |
     |---- CONNECT (Header: Bearer JWT) --->|                                  |
     |                                      |-- Validate JWT via JwtService    |
     |                                      |-- Set SecurityContext Principal  |
     |<--- CONNECTED -----------------------|                                  |
     |                                      |                                  |
     |---- SUBSCRIBE (/topic/game/{id}) --->|                                  |
     |                                      |-- Check Principal in Session     |
     |                                      |-- Query GameSession participants |
     |                                      |-- If not member: throw 403       |
     |<--- SUBSCRIBED ----------------------|                                  |
     |                                      |                                  |
     |---- SEND (/app/game/{id}/move) ---------------------------------------->|
                                                                               |-- Validate Principal == playerId
                                                                               |-- Update GameSession in Supabase
                                                                               |-- Broadcast to /topic/game/{id}
```

### 5.1 HTTP Security (`SecurityConfig` & `JwtAuthenticationFilter`)
* **Stateless**: `SessionCreationPolicy.STATELESS` (no HTTP sessions kept in heap memory).
* **CSRF**: Disabled (safe because API is token-based and STOMP-based).
* **CORS**: Configured with `allowedOriginPatterns("*")` and allowed methods `GET, POST, PUT, DELETE, OPTIONS`.
* **JWT Filter**: `JwtAuthenticationFilter` intercepts HTTP calls with `Authorization: Bearer <token>`, validates the signature, and sets `SecurityContextHolder.getContext().setAuthentication(...)`.

### 5.2 WebSocket Channel Security (`TopicSubscriptionInterceptor`)
A dedicated `ChannelInterceptor` registered on the inbound client channel (`configureClientInboundChannel`):
1. **`StompCommand.CONNECT` Interception**:
   * Reads native STOMP headers: `Authorization: Bearer <token>` (or fallback `token` header).
   * Validates cryptographic signature and expiration via `JwtService`.
   * Sets `accessor.setUser(authentication)` with a `UsernamePasswordAuthenticationToken` representing the user.
   * If missing or invalid, throws `AccessDeniedException` and drops the connection.
2. **`StompCommand.SUBSCRIBE` Interception**:
   * Targets `/topic/game/{gameId}`.
   * Extracts the authenticated `Principal` from the STOMP session.
   * Queries `GameSessionRepository.findByGameId(gameId)`.
   * Verifies that `session.isParticipant(principal.getName()) == true`.
   * If unauthorized, throws `AccessDeniedException`, preventing unauthorized spectators or eavesdroppers from accessing match moves.

### 5.3 Controller Move Verification (`GameController`)
* `@MessageMapping("/game/{gameId}/move")` accepts `Principal principal`.
* **Anti-Spoofing Check**: Compares `principal.getName()` with `action.playerId()`. If they do not match, the action is rejected with `AccessDeniedException`.
* **State Synchronization**: Updates the session in `GameSessionRepository` before returning the broadcast `GameState`.

---

## 6. Directory Structure & Key Files

```text
JavaGameServer/
├── .github/                       # CI/CD Workflows (optional)
├── .mvn/wrapper/                  # Maven Wrapper binary & settings
│   └── maven-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/com/gameserver/
│   │   │   ├── config/
│   │   │   │   ├── JwtAuthenticationFilter.java      # REST Bearer token filter
│   │   │   │   ├── SecurityConfig.java               # Spring Security 6 stateless filter chain
│   │   │   │   ├── TopicSubscriptionInterceptor.java # STOMP CONNECT & SUBSCRIBE interceptor
│   │   │   │   └── WebSocketConfig.java              # STOMP broker registry & interceptor wiring
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java               # Register & Login REST endpoints
│   │   │   │   └── GameController.java               # Move handler, room join, and health probe
│   │   │   ├── dto/
│   │   │   │   ├── AuthResponse.java                 # JWT response record
│   │   │   │   ├── GameAction.java                   # Inbound move record (playerId, moveData)
│   │   │   │   ├── GameState.java                    # Outbound state record (gameId, status, ...)
│   │   │   │   ├── LoginRequest.java                 # Login credentials record
│   │   │   │   └── RegisterRequest.java              # Registration credentials record
│   │   │   ├── model/
│   │   │   │   ├── GameSession.java                  # JPA Entity: game_sessions & participants
│   │   │   │   └── User.java                         # JPA Entity: users (BCrypt hashes)
│   │   │   ├── repository/
│   │   │   │   ├── GameSessionRepository.java        # Spring Data JPA session queries
│   │   │   │   └── UserRepository.java               # Spring Data JPA user queries
│   │   │   ├── service/
│   │   │   │   └── JwtService.java                   # HMAC-SHA256 token issuance & validation
│   │   │   └── GameServerApplication.java           # Spring Boot Application entrypoint
│   │   └── resources/
│   │       ├── static/
│   │       │   └── index.html                        # Secured STOMP web client test interface
│   │       └── application.yml                       # Low-RAM configs, HikariCP, and Virtual Threads
│   └── test/
│       ├── java/com/gameserver/
│       │   └── GameServerApplicationTests.java       # MockMvc integration & security tests
│       └── resources/
│           └── application.yml                       # In-memory H2 profile for zero-dependency builds
├── Dockerfile                                        # Multi-stage build with Alpine runtime & SerialGC
├── .dockerignore                                     # Build exclusions
├── deploy-cloudrun.sh                                # Automated bash deployment script for Cloud Run
├── deploy-cloudrun.ps1                               # Automated PowerShell deployment script
├── mvnw / mvnw.cmd                                   # Cross-platform Maven wrapper scripts
├── pom.xml                                           # Dependencies: Boot 3.3.5, JJWT 0.12.6, PostgreSQL
├── README.md                                         # Public documentation & getting started
└── CONTEXT.md                                        # Persistent AI / Architecture memory
```

---

## 7. Operational Guidelines for Future AI Agents

1. **Memory Budget Preservation**:
   * Do not add heavy dependencies (e.g., full Spring Batch, distributed cache clients, or active MQ clients) without verifying memory impact.
   * Keep `spring.threads.virtual.enabled=true` and `server.tomcat.threads.max=20`.
   * Keep `spring.jpa.open-in-view=false`.
2. **Security Integrity**:
   * Any new STOMP subscription destination must be registered and verified inside `TopicSubscriptionInterceptor`.
   * Any new move or player action must validate `principal.getName()` to prevent spoofing.
   * Passwords must always be hashed with `BCryptPasswordEncoder`.
3. **Database Transactions & Resiliency**:
   * When modifying `GameSession`, keep the Supabase database writeback inside the action method to ensure state persistence across scale-to-zero cold starts.
   * Keep HikariCP pool sized conservatively (`<= 5` connections).
