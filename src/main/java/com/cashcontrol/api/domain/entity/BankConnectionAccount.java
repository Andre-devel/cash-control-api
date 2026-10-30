package com.cashcontrol.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Conta que a Pluggy devolve para uma conexão, e a conta ou o cartão do app que recebe os
 * lançamentos dela. Sem vínculo, a conta aparece na tela mas nada é buscado.
 */
@Entity
@Table(name = "bank_connection_accounts")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BankConnectionAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "connection_id", nullable = false, updatable = false)
    private UUID connectionId;

    @Column(name = "external_account_id", nullable = false, updatable = false, length = 64)
    private String externalAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private BankAccountKind kind;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "number", length = 64)
    private String number;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "credit_card_id")
    private UUID creditCardId;
}
