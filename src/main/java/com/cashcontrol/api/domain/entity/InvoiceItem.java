package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import com.cashcontrol.api.service.MerchantKey;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "invoice_items")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"invoice", "category", "subcategory", "installmentSeries", "transaction", "tags"})
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /**
     * A descrição como o arquivo trouxe, antes de qualquer edição do usuário. NULL em
     * itens lançados à mão e em itens importados antes desta coluna existir. É dela, e
     * não de {@link #description}, que {@link #merchantKey} é derivada — ver o cabeçalho
     * da migração V28 para o porquê (a mesma diferença documentada em MerchantAlias
     * contra transactions.merchant_key).
     */
    @Column(name = "original_description", length = 255)
    private String originalDescription;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "competence_date", nullable = false)
    private LocalDate competenceDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subcategory_id")
    private Category subcategory;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installment_series_id")
    private InstallmentSeries installmentSeries;

    @Column(name = "installment_number")
    private Integer installmentNumber;

    @Column(name = "total_installments")
    private Integer totalInstallments;

    /**
     * Hash da linha da fatura em PDF que originou este item. NULL em tudo que não
     * veio de importação; é ele que faz reimportar a mesma fatura ser um no-op.
     */
    @Column(name = "external_ref", length = 64)
    private String externalRef;

    /**
     * Identidade do estabelecimento, derivada em todo insert/update por
     * {@link #deriveMerchantKey()} a partir de {@link #originalDescription} (com fallback
     * para {@link #description} quando não há original — item lançado à mão). NULL quando
     * a descrição não deixa nada identificável.
     */
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "merchant_key", length = 64)
    private String merchantKey;

    @Column(name = "is_detached", nullable = false)
    private boolean isDetached = false;

    @Column(name = "is_revolving", nullable = false)
    private boolean isRevolving = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "invoice_item_tags",
            joinColumns = @JoinColumn(name = "invoice_item_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    @CreationTimestamp
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Roda em todo insert e update, no mesmo padrão de Transaction.deriveMerchantKey:
     * nenhum call site precisa lembrar de preencher merchantKey.
     */
    @PrePersist
    @PreUpdate
    private void deriveMerchantKey() {
        this.merchantKey = MerchantKey.of(this.originalDescription != null ? this.originalDescription : this.description);
    }
}
