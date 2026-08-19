package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Como o usuário prefere ver um estabelecimento — {@code "Claude - mensalidade"} no lugar
 * do {@code "ANTHROPIC* CLAUDE SUB"} que a fatura manda todo mês.
 *
 * <p>Ver o cabeçalho da migração V27 para a diferença entre este {@code merchantKey} e o de
 * {@code Transaction}: aqui a chave vem sempre da descrição <strong>como o arquivo a
 * trouxe</strong>, nunca da renomeada, porque é o texto do arquivo que se repete de um mês
 * para o outro e serve de identidade na importação seguinte.
 */
@Entity
@Table(name = "merchant_aliases")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MerchantAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Derivada por {@code MerchantKey.of} da descrição original do arquivo. */
    @Column(name = "merchant_key", nullable = false, length = 64)
    private String merchantKey;

    /** A descrição que o usuário escolheu — o que a prévia vai pré-preencher. */
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @CreationTimestamp
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Quando o apelido foi confirmado pela última vez. É o critério de desempate quando
     * uma linha casa com mais de um apelido pelo mesmo token — vence a renomeação mais
     * recente, que é a decisão mais atual do usuário.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
