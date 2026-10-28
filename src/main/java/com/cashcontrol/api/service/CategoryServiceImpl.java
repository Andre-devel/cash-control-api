package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.HiddenSystemCategory;
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
import com.cashcontrol.api.repository.HiddenSystemCategoryRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final int MAX_SUGGESTIONS = 5;

    private final CategoryRepository categoryRepository;
    private final CategoryResolver categoryResolver;
    private final CategoryRuleRepository categoryRuleRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HiddenSystemCategoryRepository hiddenSystemCategoryRepository;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(UUID userId, boolean includeHidden, boolean includeArchived) {
        List<Category> all = new ArrayList<>();
        all.addAll(categoryRepository.findAllSystemCategories());
        all.addAll(categoryRepository.findAllByUserId(userId));
        Set<UUID> hiddenSystem = hiddenSystemCategoryRepository.findCategoryIdsByUserId(userId);

        // Subcategoria de pai oculto sai junto: só as raízes visíveis carregam filhos abaixo.
        Stream<Category> filtered = all.stream();
        if (!includeHidden) {
            filtered = filtered.filter(c -> !isHidden(c, hiddenSystem));
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
                .map(root -> toCategoryResponse(root, childrenByParent.getOrDefault(root.getId(), Collections.emptyList()), hiddenSystem))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request, UUID userId) {
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryResolver.resolve(request.parentId(), userId);

            if (parent.getParent() != null) {
                throw new BusinessRuleException("Não é possível criar uma subcategoria dentro de outra subcategoria. A profundidade máxima é 2.");
            }
        }

        UUID parentId = parent != null ? parent.getId() : null;
        if (categoryRepository.existsNameInScope(userId, parentId, request.name(), null)) {
            throw new ConflictException("Já existe uma categoria com o nome '" + request.name() + "' neste escopo.");
        }

        Category category = new Category();
        category.setUserId(userId);
        category.setParent(parent);
        category.setName(request.name());
        category.setColor(request.color());
        category.setIcon(request.icon());
        category.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);

        category = categoryRepository.save(category);
        return toCategoryResponse(category, Collections.emptyList());
    }

    @Override
    @Transactional
    public CategoryResponse editCategory(UUID id, EditCategoryRequest request, UUID userId) {
        Category category = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        if (category.isDefault()) {
            throw new BusinessRuleException("Categorias padrão do sistema não podem ser editadas.");
        }

        if (request.name() != null && !request.name().equals(category.getName())) {
            UUID parentId = category.getParent() != null ? category.getParent().getId() : null;
            if (categoryRepository.existsNameInScope(userId, parentId, request.name(), id)) {
                throw new ConflictException("Já existe uma categoria com o nome '" + request.name() + "' neste escopo.");
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

        if (category.getUserId() != null) {
            category.setHidden(hidden);
            category = categoryRepository.save(category);
            return toCategoryResponse(category, Collections.emptyList());
        }

        // Categoria do sistema é de todos: a escolha fica numa tabela por usuário (V34).
        if (hidden && !hiddenSystemCategoryRepository.existsByUserIdAndCategoryId(userId, id)) {
            HiddenSystemCategory entry = new HiddenSystemCategory();
            entry.setUserId(userId);
            entry.setCategoryId(id);
            hiddenSystemCategoryRepository.save(entry);
        } else if (!hidden) {
            hiddenSystemCategoryRepository.deleteByUserIdAndCategoryId(userId, id);
        }
        return toCategoryResponse(category, Collections.emptyList(), hidden ? Set.of(id) : Set.of());
    }

    @Override
    @Transactional
    public CategoryResponse archiveCategory(UUID id, UUID userId) {
        Category category = categoryRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

        if (category.isDefault()) {
            throw new BusinessRuleException("Categorias padrão do sistema não podem ser arquivadas.");
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
        Category category = categoryResolver.resolve(request.categoryId(), userId);
        Category subcategory = categoryResolver.resolveSubcategory(request.subcategoryId(), category, userId);

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

    /** {@code is_hidden} só vale para categorias do usuário; as do sistema vêm de {@code hiddenSystem}. */
    private static boolean isHidden(Category category, Set<UUID> hiddenSystem) {
        return category.getUserId() == null ? hiddenSystem.contains(category.getId()) : category.isHidden();
    }

    private CategoryResponse toCategoryResponse(Category category, List<Category> subcategories) {
        return toCategoryResponse(category, subcategories, Set.of());
    }

    private CategoryResponse toCategoryResponse(Category category, List<Category> subcategories,
                                                Set<UUID> hiddenSystem) {
        List<CategoryResponse> subResponses = subcategories.stream()
                .sorted(Comparator.comparingInt(Category::getSortOrder)
                        .thenComparing(c -> c.getCreatedAt() != null ? c.getCreatedAt() : Instant.EPOCH))
                .map(sub -> toCategoryResponse(sub, Collections.emptyList(), hiddenSystem))
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
                isHidden(category, hiddenSystem),
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
