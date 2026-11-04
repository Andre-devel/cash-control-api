package com.cashcontrol.api.domain.entity;

/** O que disparou a leitura de um item da Pluggy. Ver V44. */
public enum BankSyncTrigger {
    /** Varredura de hora em hora ({@code BankSyncScheduler}). */
    SCHEDULED,
    /** Evento do webhook da Pluggy. */
    WEBHOOK,
    /** "Atualizar" na tela. */
    MANUAL,
    /** Cadastro do item, pelo widget ou pelo webhook {@code item/created}. */
    REGISTER
}
