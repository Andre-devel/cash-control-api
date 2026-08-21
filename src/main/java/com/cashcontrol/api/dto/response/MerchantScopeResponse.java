package com.cashcontrol.api.dto.response;

/**
 * O que a tela de edição precisa saber sobre o estabelecimento de um item antes de exibir os
 * checkboxes de memória — em especial {@code relatedItemCount}, para não mostrar "aplicar aos
 * outros 0 lançamentos" quando não há nenhum.
 */
public record MerchantScopeResponse(
        String merchantKey,
        String originalDescription,
        String currentAliasName,
        long relatedItemCount
) {}
