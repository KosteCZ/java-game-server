package com.gameserver.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * GameSession JPA entity mapped to 'game_sessions' table in Supabase PostgreSQL.
 * Tracks match state and authorized participant players.
 */
@Entity
@Table(name = "game_sessions")
public class GameSession implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", unique = true, nullable = false, length = 100)
    private String gameId;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "IN_PROGRESS";

    @Column(name = "last_move_by", length = 50)
    private String lastMoveBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "game_session_participants", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "username")
    private Set<String> participants = new HashSet<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public GameSession() {
    }

    public GameSession(String gameId) {
        this.gameId = gameId;
        this.status = "IN_PROGRESS";
        this.updatedAt = Instant.now();
    }

    public boolean isParticipant(String username) {
        if (username == null) return false;
        return participants.contains(username);
    }

    public void addParticipant(String username) {
        if (username != null) {
            participants.add(username);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastMoveBy() {
        return lastMoveBy;
    }

    public void setLastMoveBy(String lastMoveBy) {
        this.lastMoveBy = lastMoveBy;
    }

    public Set<String> getParticipants() {
        return participants;
    }

    public void setParticipants(Set<String> participants) {
        this.participants = participants;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
