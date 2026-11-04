package com.cashcontrol.api.repository;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.RecurrenceSuggestionDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface RecurrenceSuggestionDismissalRepository
        extends JpaRepository<RecurrenceSuggestionDismissal, RecurrenceSuggestionDismissal.Key> {

    @Query("SELECT d.id.pattern FROM RecurrenceSuggestionDismissal d WHERE d.id.userId = :userId")
    Set<String> findPatternsByUserId(@Param("userId") UUID userId);

    /** Registra a recusa; recusar de novo o mesmo padrão não faz nada. */
    @Modifying
    @Query(value = "INSERT INTO recurrence_suggestion_dismissals (user_id, pattern) VALUES (:userId, :pattern) "
                   + "ON CONFLICT (user_id, pattern) DO NOTHING", nativeQuery = true)
    void insertIgnoringDuplicate(@Param("userId") UUID userId, @Param("pattern") String pattern);
}
