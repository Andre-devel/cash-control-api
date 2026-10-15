package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolve as categorias que um request referencia, aceitando só as que o usuário enxerga:
 * as do sistema e as dele. {@code findById} puro aceitaria o UUID de uma categoria de outro
 * usuário e devolveria o nome dela na resposta.
 */
@Component
@RequiredArgsConstructor
public class CategoryResolver {

    private final CategoryRepository categoryRepository;

    /** {@code null} quando nenhuma categoria foi informada. */
    public Category resolve(UUID categoryId, UUID userId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findByIdVisibleToUser(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
    }

    /** {@code null} quando nenhuma subcategoria foi informada; recusa a que não é filha de {@code category}. */
    public Category resolveSubcategory(UUID subcategoryId, Category category, UUID userId) {
        if (subcategoryId == null) {
            return null;
        }
        Category subcategory = categoryRepository.findByIdVisibleToUser(subcategoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Subcategory not found: " + subcategoryId));
        if (category == null || subcategory.getParent() == null
                || !subcategory.getParent().getId().equals(category.getId())) {
            throw new BusinessRuleException("A subcategoria não pertence à categoria informada.");
        }
        return subcategory;
    }
}
