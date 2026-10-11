package com.gameserver.repository;

import com.gameserver.model.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameSessionRepository extends JpaRepository<GameSession, Long> {

    Optional<GameSession> findByGameId(String gameId);

    boolean existsByGameId(String gameId);
}
