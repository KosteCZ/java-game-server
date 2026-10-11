# Lightweight High-Performance Multiplayer Game Server (Spring Boot 3 + Java 21)

A production-ready, ultra-lightweight multiplayer game server scaffolded with **Spring Boot 3.3.5** and **Java 21**, specially engineered to run inside **constrained 512MB RAM environments** such as the **Koyeb Free Tier (Nano instance)**, paired with an external **Supabase PostgreSQL** database.

---

## 🚀 Key Architectural Highlights & 512MB RAM Optimizations

| Area | Configuration | Rationale / Benefit |
| :--- | :--- | :--- |
| **Java Platform** | Java 21 LTS | Project Loom Virtual Threads & memory-efficient runtime. |
| **Thread Model** | `spring.threads.virtual.enabled=true` | Replaces heavyweight OS threads for I/O tasks with lightweight virtual threads; scales WebSocket connections without running out of native thread stack memory. |
| **Tomcat Threads** | `server.tomcat.threads.max: 20`<br>`server.tomcat.threads.min-spare: 2` | Strictly bounds Tomcat worker threads to prevent massive memory overhead under burst traffic. |
| **JMX Management** | `spring.jmx.enabled=false` | Disables MBean registration, saving heap and Metaspace memory. |
| **Connection Pool** | HikariCP (`max: 5`, `min-idle: 1`) | Prevents exhausting Supabase Free Tier connection limits and minimizes pooled connection footprint. |
| **JPA Open Session**| `spring.jpa.open-in-view=false` | Ensures database connections are not kept open throughout long-lived WebSocket requests. |
| **JVM Garbage Collector** | `-XX:+UseSerialGC` | Minimizes GC memory footprint compared to G1GC (saving ~20-40MB of memory overhead on single-core / low-RAM containers). |
| **Container RAM Tuning** | `-XX:+UseContainerSupport`<br>`-XX:MaxRAMPercentage=70.0` | Keeps heap around ~350MB, reserving ~160MB safe headroom for Metaspace, off-heap buffers, thread stacks, and OS. |
| **Crash Protection** | `-XX:+ExitOnOutOfMemoryError` | Automatically terminates the JVM on OOM so container orchestrators (like Koyeb) instantly reboot a clean container instead of freezing. |

---

## 🛠️ Project Structure

```text
JavaGameServer/
├── .mvn/wrapper/                  # Maven wrapper binaries & configurations
├── src/
│   ├── main/
│   │   ├── java/com/gameserver/
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java     # Stateless security, CORS, JWT hooks
│   │   │   │   └── WebSocketConfig.java    # STOMP broker (/topic) & endpoints (/ws-game)
│   │   │   ├── controller/
│   │   │   │   └── GameController.java     # Move handler & Koyeb health probe (/api/health)
│   │   │   ├── dto/
│   │   │   │   ├── GameAction.java         # Immutable record for player moves
│   │   │   │   └── GameState.java          # Immutable record for game state snapshots
│   │   │   └── GameServerApplication.java  # Main application entry point
│   │   └── resources/
│   │       └── application.yml             # Optimized configuration & Supabase DataSource
│   └── test/
│       ├── java/com/gameserver/
│       │   └── GameServerApplicationTests.java # Context & REST integration tests
│       └── resources/
│           └── application.yml             # In-memory H2 profile for zero-dependency builds
├── Dockerfile                             # Multi-stage build with 512MB RAM tuning
├── .dockerignore                          # Container build exclusions
├── mvnw & mvnw.cmd                        # Cross-platform Maven wrapper scripts
├── pom.xml                                # Project dependencies & Java 21 build plugins
└── README.md
```

---

## 🔌 WebSocket STOMP Protocol Specification

### 1. Connection Endpoint
* **Handshake URL**: `ws://<host>/ws-game` (Native WebSocket) or `http://<host>/ws-game` (SockJS fallback)
* **Allowed Origins**: `*` (Configured via `allowedOriginPatterns` for cross-origin frontend support)

### 2. Message Routing
* **Inbound Application Destination**: `/app/game/{gameId}/move`
  * Sent by clients to trigger a move.
  * Handled by [`GameController.java`](file:///D:/DEVELOPMENT/WEB/JavaGameServer/src/main/java/com/gameserver/controller/GameController.java).
* **Outbound Topic Destination**: `/topic/game/{gameId}`
  * Subscribed to by all players and spectators in the match.
  * Verified game state updates are broadcast here.

### 3. Payload Examples

**Client Action (`GameAction` record):**
```json
{
  "playerId": "player_abc123",
  "moveData": {
    "x": 4,
    "y": 2,
    "action": "place_token"
  }
}
```

**Server Broadcast (`GameState` record):**
```json
{
  "gameId": "room_99",
  "status": "IN_PROGRESS",
  "lastMoveBy": "player_abc123",
  "moveData": {
    "x": 4,
    "y": 2,
    "action": "place_token"
  },
  "updatedAt": 1776023245000
}
```

---

## 🌐 JavaScript Frontend Client Example

Using `@stomp/stompjs`:

```javascript
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'wss://your-app.koyeb.app/ws-game',
  onConnect: () => {
    console.log('Connected to game server!');

    const gameId = 'room_101';

    // 1. Subscribe to match state updates
    client.subscribe(`/topic/game/${gameId}`, (message) => {
      const state = JSON.parse(message.body);
      console.log('New Game State received:', state);
    });

    // 2. Publish a move action
    client.publish({
      destination: `/app/game/${gameId}/move`,
      body: JSON.stringify({
        playerId: 'user_42',
        moveData: { x: 3, y: 7, card: 'fireball' }
      })
    });
  }
});

client.activate();
```

---

## 🗄️ Supabase Database Integration

To connect to your external **Supabase PostgreSQL** instance, set the following environment variables (in your local `.env` or in Koyeb Secret Environment Variables):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://<project-ref>.supabase.co:5432/postgres?sslmode=require
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_supabase_database_password
```

> **Tip for Supabase**: If using connection pooling with Supabase PgBouncer or Supavisor, use port `6543` in session or transaction mode.

---

## 🚢 Koyeb Deployment Guide

1. **Push to GitHub**:
   Commit and push this repository to GitHub.
2. **Create New Service on Koyeb**:
   - Choose **GitHub repository** or **Dockerfile**.
   - Select the **Nano Instance** (Free Tier - 0.1 vCPU, 512 MB RAM).
3. **Environment Variables**:
   Add the following variables in the Koyeb console:
   - `PORT`: `8080`
   - `SPRING_DATASOURCE_URL`: `jdbc:postgresql://<ref>.supabase.co:5432/postgres?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME`: `postgres`
   - `SPRING_DATASOURCE_PASSWORD`: `<your-password>`
4. **Health Check**:
   - Protocol: `HTTP`
   - Path: `/api/health`
   - Port: `8080`

---

## 💻 Local Building & Testing

### Build with Maven Wrapper:
```bash
./mvnw clean package
```
*(On Windows PowerShell: `.\mvnw.cmd clean package`)*

### Run Locally:
```bash
java -jar target/java-game-server-0.0.1-SNAPSHOT.jar
```
Or with Maven directly:
```bash
./mvnw spring-boot:run
```
