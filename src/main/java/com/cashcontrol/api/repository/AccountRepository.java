package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByUserIdAndDeletedAtIsNull(UUID userId);

    List<Account> findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNull(UUID userId);

    Optional<Account> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndNameAndDeletedAtIsNull(UUID userId, String name);
}
