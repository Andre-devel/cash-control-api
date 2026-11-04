package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.CategoryRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Casa a descrição de uma transação com as regras de categorização do usuário.
 *
 * <p>Extraído para ser compartilhado entre a criação avulsa de transação e a
 * importação de extrato: a importação precisa aplicar exatamente a mesma regra,
 * mas sobre centenas de linhas, com a lista de regras carregada uma vez só em
 * vez de uma consulta por linha.
 */
@Component
public class CategoryRuleMatcher {

    /** {@link #match(List, String, String)} sem contraparte. */
    public Optional<CategoryRule> match(List<CategoryRule> rules, String description) {
        return match(rules, description, null);
    }

    /**
     * Primeira regra cujo padrão aparece na descrição ou no nome da contraparte, ignorando
     * maiúsculas. O extrato do Pix costuma trazer só "Pix enviado" na descrição; quem recebeu
     * vem na contraparte, e é por ela que uma regra "Fulano" acha o lançamento.
     *
     * @param rules            regras ativas do usuário, já ordenadas por prioridade
     * @param counterpartyName quem está do outro lado, quando o banco informa; pode ser {@code null}
     */
    public Optional<CategoryRule> match(List<CategoryRule> rules, String description, String counterpartyName) {
        String text = matchText(description, counterpartyName);
        if (text == null || rules.isEmpty()) {
            return Optional.empty();
        }
        String lowerText = text.toLowerCase();
        return rules.stream()
                .filter(rule -> lowerText.contains(rule.getPattern().toLowerCase()))
                .findFirst();
    }

    /** {@code description + " " + counterpartyName}, só com as partes que existem. */
    static String matchText(String description, String counterpartyName) {
        boolean hasCounterparty = counterpartyName != null && !counterpartyName.isBlank();
        if (description == null) {
            return hasCounterparty ? counterpartyName : null;
        }
        return hasCounterparty ? description + " " + counterpartyName : description;
    }
}
