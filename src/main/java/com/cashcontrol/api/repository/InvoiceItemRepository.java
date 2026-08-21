package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {

    List<InvoiceItem> findAllByInvoice_IdAndCancelledAtIsNull(UUID invoiceId);

    Page<InvoiceItem> findAllByInvoice_IdAndCancelledAtIsNull(UUID invoiceId, Pageable pageable);

    Optional<InvoiceItem> findByIdAndUserId(UUID id, UUID userId);

    Optional<InvoiceItem> findByTransaction_Id(UUID transactionId);

    List<InvoiceItem> findAllByInstallmentSeries_Id(UUID installmentSeriesId);

    /**
     * Quais destes {@code externalRef} já existem na fatura — a checagem de duplicata
     * da importação de fatura em PDF.
     *
     * <p>Uma consulta para o arquivo inteiro, não uma por linha. Escopada por fatura
     * porque é esse o alcance do índice único {@code uidx_invoice_items_external_ref}:
     * a mesma compra parcelada aparece em faturas de meses diferentes e cada ocorrência
     * é um lançamento legítimo.
     */
    @Query("SELECT ii.externalRef FROM InvoiceItem ii " +
           "WHERE ii.userId = :userId AND ii.invoice.id = :invoiceId AND ii.externalRef IN :externalRefs")
    List<String> findExistingExternalRefs(
            @Param("userId") UUID userId,
            @Param("invoiceId") UUID invoiceId,
            @Param("externalRefs") Collection<String> externalRefs);

    /**
     * Os itens que já ocupam estas chaves, em qualquer fatura do usuário.
     *
     * <p>Usada na conciliação: quando o PDF traz uma parcela que a importação do mês
     * anterior já criou por estimativa, é este item que recebe o valor real. Sem escopo de
     * fatura porque a parcela estimada foi gravada na fatura que a importação deduziu, que
     * é justamente a que pode não ser a que o PDF de agora está descrevendo.
     */
    @Query("SELECT ii FROM InvoiceItem ii " +
           "WHERE ii.userId = :userId AND ii.externalRef IN :externalRefs")
    List<InvoiceItem> findAllByExternalRefIn(
            @Param("userId") UUID userId,
            @Param("externalRefs") Collection<String> externalRefs);

    @Query("SELECT ii.category.id, ii.category.name, SUM(ii.amount) as total " +
           "FROM InvoiceItem ii " +
           "WHERE ii.invoice.creditCard.id = :cardId AND ii.userId = :userId " +
           "AND ii.cancelledAt IS NULL " +
           // O CAST não é decorativo: sem ele o Postgres recebe `? IS NULL` sem contexto
           // de tipo e responde "could not determine data type of parameter". Mesmo
           // motivo dos filtros de data em TransactionRepository.
           "AND (CAST(:from AS LocalDate) IS NULL OR ii.competenceDate >= :from) " +
           "AND (CAST(:to AS LocalDate) IS NULL OR ii.competenceDate <= :to) " +
           "AND ii.category IS NOT NULL " +
           "GROUP BY ii.category.id, ii.category.name " +
           "ORDER BY total DESC")
    List<Object[]> findSpendingByCategory(@Param("cardId") UUID cardId, @Param("userId") UUID userId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(ii.amount), 0) FROM InvoiceItem ii " +
           "WHERE ii.invoice.creditCard.id = :cardId " +
           "AND ii.cancelledAt IS NULL " +
           "AND ii.invoice.status IN :statuses")
    BigDecimal sumAmountByCardIdAndInvoiceStatuses(@Param("cardId") UUID cardId,
                                                   @Param("statuses") List<InvoiceStatus> statuses);

    /**
     * Quantos itens não cancelados cada fatura tem, e quantos deles vieram de importação
     * ({@code externalRef IS NOT NULL}) — a coluna "N · M importados" da lista de faturas.
     * Uma consulta para a página inteira, não uma por fatura.
     */
    @Query("SELECT ii.invoice.id, COUNT(ii), SUM(CASE WHEN ii.externalRef IS NOT NULL THEN 1L ELSE 0L END) " +
           "FROM InvoiceItem ii WHERE ii.invoice.id IN :invoiceIds AND ii.cancelledAt IS NULL " +
           "GROUP BY ii.invoice.id")
    List<Object[]> countItemsByInvoiceIds(@Param("invoiceIds") Collection<UUID> invoiceIds);

    /**
     * Os demais itens do mesmo estabelecimento — usada tanto para o "aplicar aos outros N"
     * da edição quanto para o {@code GET .../merchant} que informa esse N antes de editar.
     * Exclui o próprio item que está sendo editado.
     */
    List<InvoiceItem> findAllByUserIdAndMerchantKeyAndCancelledAtIsNullAndIdNot(
            UUID userId, String merchantKey, UUID excludedId);
}
