package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"creditCard", "items"})
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_card_id", nullable = false)
    private CreditCard creditCard;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.OPEN;

    @Column(name = "reference_month", nullable = false, length = 7)
    private String referenceMonth;

    @Column(name = "closing_date", nullable = false)
    private LocalDate closingDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * {@code true} quando o status PAID veio da quitação simples ({@code settle} / o
     * {@code alreadyPaid} do import) em vez de um pagamento real via {@code payInvoice}.
     * É o que distingue uma fatura que pode ser reaberta sem deixar rastro (nenhuma
     * transação de pagamento foi criada) de uma que não pode (reabrir deixaria órfã a
     * transação de pagamento na conta).
     */
    @Column(name = "paid_without_transaction", nullable = false)
    private boolean paidWithoutTransaction = false;

    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY)
    private List<InvoiceItem> items = new ArrayList<>();

    @CreationTimestamp
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soma {@code delta} (negativo para estornar) ao total, mantendo o status coerente.
     *
     * <p>Só a fatura que já recebeu pagamento precisa de ajuste. Quitada sem transação ("marcar
     * como paga") é uma declaração do usuário de que está tudo pago, então o pago acompanha o
     * total. Paga por pagamento real não pode inventar dinheiro: o status sai do que foi pago
     * contra o total novo ({@link #applyPaidAmount}) — o que passar do pago fica PARCIAL e o
     * restante vai para o item rotativo da fatura seguinte ({@code CreditCardService.syncRevolvingItem}).
     */
    public void addToTotal(BigDecimal delta) {
        totalAmount = totalAmount.add(delta);
        if (status != InvoiceStatus.PAID && status != InvoiceStatus.PARTIAL) {
            return;
        }
        if (paidWithoutTransaction) {
            paidAmount = totalAmount;
        } else {
            applyPaidAmount(paidAmount, LocalDate.now());
        }
    }

    /**
     * Grava o valor pago e o status que ele dá: PAGA quando cobre o total, PARCIAL quando cobre
     * parte, e o status da data quando nada foi pago (o estorno do único pagamento).
     */
    public void applyPaidAmount(BigDecimal paid, LocalDate today) {
        paidAmount = paid;
        if (paid.signum() > 0 && paid.compareTo(totalAmount) >= 0) {
            status = InvoiceStatus.PAID;
        } else if (paid.signum() > 0) {
            status = InvoiceStatus.PARTIAL;
        } else {
            status = InvoiceStatus.OPEN;
            refreshTimelineStatus(today);
        }
    }

    /** O que esta fatura empurra para a seguinte como saldo rotativo. */
    public BigDecimal revolvingBalance() {
        return status == InvoiceStatus.PARTIAL ? totalAmount.subtract(paidAmount) : BigDecimal.ZERO;
    }

    /**
     * Status de uma fatura que ainda não recebeu pagamento, pela data: ABERTA até o dia do
     * fechamento (compras desse dia ainda entram nela, ver InvoiceCycleCalculator), FECHADA
     * depois dele e VENCIDA depois do vencimento. PAGA e PARCIAL não mudam com o tempo.
     *
     * <p>A mesma regra roda em lote no job noturno ({@code InvoiceRepository.markOverdue} e
     * {@code markClosed}); aqui ela vale para a fatura que nasce ou reabre fora de hora.
     */
    public void refreshTimelineStatus(LocalDate today) {
        if (status == InvoiceStatus.PAID || status == InvoiceStatus.PARTIAL) {
            return;
        }
        if (dueDate.isBefore(today)) {
            status = InvoiceStatus.OVERDUE;
        } else if (closingDate.isBefore(today)) {
            status = InvoiceStatus.CLOSED;
        } else {
            status = InvoiceStatus.OPEN;
        }
    }
}
