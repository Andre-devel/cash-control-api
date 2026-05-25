package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {

    Optional<UserConsent> findTopByUserIdAndRevokedAtIsNullOrderByAcceptedAtDesc(UUID userId);

    List<UserConsent> findByUserIdOrderByAcceptedAtDesc(UUID userId);
}