package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(String tokenHash);

    @Modifying
    @Transactional
    @Query("UPDATE PasswordResetToken t SET t.invalidatedAt = CURRENT_TIMESTAMP " +
           "WHERE t.user.id = :userId AND t.consumedAt IS NULL AND t.invalidatedAt IS NULL")
    int invalidateActiveTokensForUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    void deleteByExpiresAtBeforeAndConsumedAtIsNotNull(Instant cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken t WHERE t.createdAt < :cutoff AND t.consumedAt IS NOT NULL")
    int deleteConsumedBefore(@Param("cutoff") Instant cutoff);
}