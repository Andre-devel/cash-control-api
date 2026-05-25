package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AccountLockout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountLockoutRepository extends JpaRepository<AccountLockout, UUID> {

    Optional<AccountLockout> findByUserIdAndUnlockedAtIsNull(UUID userId);
}