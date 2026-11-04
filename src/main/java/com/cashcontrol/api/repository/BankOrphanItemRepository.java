package com.cashcontrol.api.repository;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.BankOrphanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankOrphanItemRepository extends JpaRepository<BankOrphanItem, String> {

    List<BankOrphanItem> findAllByUserIdOrderByFirstSeenAtDesc(UUID userId);

    Optional<BankOrphanItem> findByItemIdAndUserId(String itemId, UUID userId);

    /** Registra o item; o mesmo item anunciado de novo mantém a data em que apareceu primeiro. */
    @Modifying
    @Query(value = "INSERT INTO bank_orphan_items (item_id, user_id, connector_name) VALUES (:itemId, :userId, :connectorName) "
                   + "ON CONFLICT (item_id) DO UPDATE SET user_id = EXCLUDED.user_id, connector_name = EXCLUDED.connector_name",
            nativeQuery = true)
    void upsert(@Param("itemId") String itemId, @Param("userId") UUID userId, @Param("connectorName") String connectorName);

    @Modifying
    @Query("DELETE FROM BankOrphanItem o WHERE o.itemId = :itemId")
    void deleteByItemId(@Param("itemId") String itemId);
}
