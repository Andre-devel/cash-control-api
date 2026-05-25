package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {

    int countByUserIdAndWasSuccessfulFalseAndAttemptedAtAfter(UUID userId, Instant since);

    int countByIpAddressMaskedAndAttemptedAtAfter(String ipAddressMasked, Instant since);
}