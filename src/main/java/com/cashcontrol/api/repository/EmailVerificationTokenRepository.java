package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(String tokenHash);

    List<EmailVerificationToken> findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE EmailVerificationToken t SET t.invalidatedAt = CURRENT_TIMESTAMP " +
           "WHERE t.user.id = :userId AND t.consumedAt IS NULL AND t.invalidatedAt IS NULL")
    int invalidateActiveTokensForUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailVerificationToken t WHERE " +
           "(t.consumedAt IS NOT NULL AND t.consumedAt < :cutoff) OR " +
           "(t.invalidatedAt IS NOT NULL AND t.invalidatedAt < :cutoff)")
    int deleteConsumedOrInvalidatedBefore(@Param("cutoff") Instant cutoff);
}