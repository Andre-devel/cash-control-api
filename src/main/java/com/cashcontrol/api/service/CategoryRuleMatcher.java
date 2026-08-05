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

    /**
     * Primeira regra cujo padrão aparece na descrição, ignorando maiúsculas.
     *
     * @param rules regras ativas do usuário, já ordenadas por prioridade
     */
    public Optional<CategoryRule> match(List<CategoryRule> rules, String description) {
        if (description == null || rules.isEmpty()) {
            return Optional.empty();
        }
        String lowerDescription = description.toLowerCase();
        return rules.stream()
                .filter(rule -> lowerDescription.contains(rule.getPattern().toLowerCase()))
                .findFirst();
    }
}
