package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.CreditCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreditCardRepository extends JpaRepository<CreditCard, UUID> {

    List<CreditCard> findAllByUserIdAndDeletedAtIsNull(UUID userId);

    List<CreditCard> findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNull(UUID userId);

    Optional<CreditCard> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndNameAndDeletedAtIsNull(UUID userId, String name);

    boolean existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(UUID userId, String name, UUID excludeId);
}
