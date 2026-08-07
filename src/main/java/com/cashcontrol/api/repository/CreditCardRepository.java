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

    /**
     * Cartões cujos 4 últimos dígitos casam com uma seção da fatura em PDF.
     *
     * <p>Lista e não {@code Optional}: nada impede o usuário de ter dois cartões
     * terminando igual, e nesse caso a importação prefere não sugerir nada a sugerir
     * o errado.
     */
    List<CreditCard> findAllByUserIdAndLast4DigitsAndDeletedAtIsNull(UUID userId, String last4Digits);

    boolean existsByUserIdAndNameAndDeletedAtIsNull(UUID userId, String name);

    boolean existsByUserIdAndNameAndDeletedAtIsNullAndIdNot(UUID userId, String name, UUID excludeId);
}
