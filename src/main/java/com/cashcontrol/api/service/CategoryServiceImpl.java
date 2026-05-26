package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRuleRequest;
import com.cashcontrol.api.dto.request.EditCategoryRequest;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.CategoryRuleResponse;
import com.cashcontrol.api.dto.response.CategorySuggestionResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final int MAX_SUGGESTIONS = 5;

    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(UUID userId, boolean includeHidden, boolean includeArchived) {
        List<Category> all = new ArrayList<>();
        all.addAll(categoryRepository.findAllSystemCategories());
        all.addAll(categoryRepository.findAllByUserId(userId));

        Stream<Category> filtered = all.stream();
        if (!includeHidden) {
            filtered = filtered.filter(c -> !c.isHidden());
        }
        if (!includeArchived) {
            filtered = filtered.filter(c -> !c.isArchived());
        }

        List<Category> visible = filtered.collect(Collectors.toList());

        Map<UUID, List<Category>> childrenByParent = visible.stream()
                .filter(c -> c.getParent() != null)
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));

        return visible.stream()
                .filter(c -> c.getParent() == null)
                .sorted(Comparator.comparingInt(Category::getSortOrder)
                        .thenComparing(c -> c.getCreatedAt() != null ? c.getCreatedAt() : Instant.EPOCH))
                .map(root -> toCategoryResponse(root, childrenByParent.getOrDefault(root.getId(), Collections.emptyList())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request, UUID userId) {
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category not found: " + request.parentId()));

            if (parent.getParent() != null) {
                throw new BusinessRuleException("Cannot create a subcategory under another subcategory. Maximum depth is 2.");
            }
        }

        UUID parentId = parent != null ? parent.getId() : null;
        if (categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNull(userId, parentId, request.name())) {
            throw new ConflictException("A category with name '" + request.name() + "' already exists in this scope.");
        }

        Category category = new Category();
        category.setUserId(userId);
        category.setParent(parent);
        category.setName(request.name());
        category.setColor(request.color());
        category.setIcon(request.icon());
        category.setSortOrder(request.sortOrder());

        category = categoryRepository.save(category);
        return toCategoryResponse(category, Collections.emptyList());
    }

    @Override
    @Transactional
    public CategoryResponse editCategory(UUID id, EditCategoryRequest request, UUID userId) {
        Category category = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        if (category.isDefault()) {
            throw new BusinessRuleException("System default categories cannot be edited.");
        }

        if (request.name() != null && !request.name().equals(category.getName())) {
            UUID parentId = category.getParent() != null ? category.getParent().getId() : null;
            if (categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNullAndIdNot(userId, parentId, request.name(), id)) {
                throw new ConflictException("A category with name '" + request.name() + "' already exists in this scope.");
            }
            category.setName(request.name());
        }

        if (request.color() != null) category.setColor(request.color());
        if (request.icon() != null) category.setIcon(request.icon());
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());

        category = categoryRepository.save(category);
        return toCategoryResponse(category, Collections.emptyList());
    }

    @Override
    @Transactional
    public CategoryResponse setHidden(UUID id, boolean hidden, UUID userId) {
        Category category = categoryRepository.findByIdVisibleToUser(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        category.setHidden(hidden);
        category = categoryRepository.save(category);
        return toCategoryResponse(category, Collections.emptyList());
    }

    @Override
    @Transactional
    public CategoryResponse archiveCategory(UUID id, UUID userId) {
        Category category = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        if (category.isDefault()) {
            throw new BusinessRuleException("System default categories cannot be archived.");
        }

        Instant now = Instant.now();
        category.setArchived(true);
        category.setArchivedAt(now);
        category = categoryRepository.save(category);

        List<Category> subcategories = categoryRepository.findSubcategoriesByUserIdAndParentId(userId, id);
        for (Category sub : subcategories) {
            sub.setArchived(true);
            sub.setArchivedAt(now);
        }
        if (!subcategories.isEmpty()) {
            categoryRepository.saveAll(subcategories);
        }

        return toCategoryResponse(category, Collections.emptyList());
    }

    @Override
    @Transactional
    public CategoryResponse unarchiveCategory(UUID id, UUID userId) {
        Category category = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        category.setArchived(false);
        category.setArchivedAt(null);
        category = categoryRepository.save(category);
        return toCategoryResponse(category, Collections.emptyList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategorySuggestionResponse> suggestCategory(String description, UUID userId) {
        PageRequest topN = PageRequest.of(0, MAX_SUGGESTIONS);

        List<Object[]> matches = new ArrayList<>();
        if (description != null && !description.isBlank()) {
            matches = transactionRepository.findTopCategoriesByDescriptionText(userId, description.trim(), topN);
        }

        if (matches.isEmpty()) {
            matches = transactionRepository.findTopCategoriesByFrequency(userId, topN);
        }

        List<CategorySuggestionResponse> suggestions = new ArrayList<>();
        for (Object[] row : matches) {
            UUID categoryId = (UUID) row[0];
            UUID subcategoryId = row[1] != null ? (UUID) row[1] : null;
            long matchCount = ((Number) row[2]).longValue();

            Category cat = categoryRepository.findById(categoryId).orElse(null);
            if (cat == null) continue;

            Category subcat = subcategoryId != null ? categoryRepository.findById(subcategoryId).orElse(null) : null;

            suggestions.add(new CategorySuggestionResponse(
                    cat.getId(),
                    cat.getName(),
                    subcat != null ? subcat.getId() : null,
                    subcat != null ? subcat.getName() : null,
                    matchCount
            ));
        }

        return suggestions;
    }

    @Override
    @Transactional
    public CategoryRuleResponse createRule(CreateCategoryRuleRequest request, UUID userId) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));

        Category subcategory = null;
        if (request.subcategoryId() != null) {
            subcategory = categoryRepository.findById(request.subcategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Subcategory not found: " + request.subcategoryId()));
            if (subcategory.getParent() == null || !subcategory.getParent().getId().equals(category.getId())) {
                throw new BusinessRuleException("Subcategory does not belong to the specified category.");
            }
        }

        Account account = null;
        if (request.accountId() != null) {
            account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));
        }

        CategoryRule rule = new CategoryRule();
        rule.setUserId(userId);
        rule.setPattern(request.pattern());
        rule.setCategory(category);
        rule.setSubcategory(subcategory);
        rule.setAccount(account);
        rule.setPriority(request.priority());

        rule = categoryRuleRepository.save(rule);
        return toCategoryRuleResponse(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRuleResponse> listRules(UUID userId) {
        return categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId).stream()
                .map(this::toCategoryRuleResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteRule(UUID id, UUID userId) {
        CategoryRule rule = categoryRuleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category rule not found: " + id));
        categoryRuleRepository.delete(rule);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private CategoryResponse toCategoryResponse(Category category, List<Category> subcategories) {
        List<CategoryResponse> subResponses = subcategories.stream()
                .sorted(Comparator.comparingInt(Category::getSortOrder)
                        .thenComparing(c -> c.getCreatedAt() != null ? c.getCreatedAt() : Instant.EPOCH))
                .map(sub -> toCategoryResponse(sub, Collections.emptyList()))
                .collect(Collectors.toList());

        return new CategoryResponse(
                category.getId(),
                category.getUserId(),
                category.getParent() != null ? category.getParent().getId() : null,
                category.getParent() != null ? category.getParent().getName() : null,
                category.getName(),
                category.getColor(),
                category.getIcon(),
                category.getSortOrder(),
                category.isDefault(),
                category.isHidden(),
                category.isArchived(),
                category.getArchivedAt(),
                subResponses,
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }

    private CategoryRuleResponse toCategoryRuleResponse(CategoryRule rule) {
        return new CategoryRuleResponse(
                rule.getId(),
                rule.getUserId(),
                rule.getPattern(),
                rule.getCategory() != null ? rule.getCategory().getId() : null,
                rule.getCategory() != null ? rule.getCategory().getName() : null,
                rule.getSubcategory() != null ? rule.getSubcategory().getId() : null,
                rule.getSubcategory() != null ? rule.getSubcategory().getName() : null,
                rule.getAccount() != null ? rule.getAccount().getId() : null,
                rule.getAccount() != null ? rule.getAccount().getName() : null,
                rule.getPriority(),
                rule.isActive(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
