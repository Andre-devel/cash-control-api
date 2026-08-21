package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Correção de um lançamento já importado (ou lançado à mão): descrição e categoria, o mesmo
 * par que a prévia do import de fatura deixa editar antes de confirmar.
 *
 * @param rememberMerchant grava a descrição como o apelido deste estabelecimento
 *                          (MerchantAliasService), para pré-preencher a próxima importação
 * @param applyToHistory   aplica a mesma descrição e categoria aos demais lançamentos não
 *                          cancelados deste estabelecimento
 */
public record UpdateInvoiceItemRequest(
        @NotBlank @Size(max = 255) String description,
        UUID categoryId,
        UUID subcategoryId,
        boolean rememberMerchant,
        boolean applyToHistory
) {}
