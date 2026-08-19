package com.cashcontrol.api.dto.response;

/**
 * De onde veio a categoria sugerida numa linha de importação, na ordem em que
 * {@code CategorySuggester} as resolve.
 */
public enum SuggestionSource {

    /** Casou com uma regra de categorização ativa do usuário. */
    RULE,

    /** Categoria mais usada pelo usuário para esse estabelecimento, sem regra que casasse. */
    HISTORY,

    /** Nem regra nem histórico: a linha precisa da categorização do usuário. */
    NONE
}
