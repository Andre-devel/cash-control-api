package com.cashcontrol.api;

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
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.CategoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryRuleRepository categoryRuleRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @InjectMocks private CategoryServiceImpl categoryService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    // ── listCategories ────────────────────────────────────────────────────────

    @Test
    void listCategories_returnsSystemAndUserCategories() {
        Category sysRoot = buildCategory(null, null, "Food", true, false, false);
        Category userRoot = buildCategory(userId, null, "My Category", false, false, false);
        Category userSub = buildCategory(userId, userRoot, "Sub", false, false, false);

        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of(sysRoot));
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(userRoot, userSub));

        List<CategoryResponse> result = categoryService.listCategories(userId, false, false);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CategoryResponse::name).containsExactlyInAnyOrder("Food", "My Category");
    }

    @Test
    void listCategories_excludesHiddenByDefault() {
        Category visible = buildCategory(userId, null, "Visible", false, false, false);
        Category hidden = buildCategory(userId, null, "Hidden", false, true, false);

        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(visible, hidden));

        List<CategoryResponse> result = categoryService.listCategories(userId, false, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Visible");
    }

    @Test
    void listCategories_includesHiddenWhenRequested() {
        Category visible = buildCategory(userId, null, "Visible", false, false, false);
        Category hidden = buildCategory(userId, null, "Hidden", false, true, false);

        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(visible, hidden));

        List<CategoryResponse> result = categoryService.listCategories(userId, true, false);

        assertThat(result).hasSize(2);
    }

    @Test
    void listCategories_excludesArchivedByDefault() {
        Category active = buildCategory(userId, null, "Active", false, false, false);
        Category archived = buildCategory(userId, null, "Archived", false, false, true);
        ReflectionTestUtils.setField(archived, "archivedAt", Instant.now());

        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(active, archived));

        List<CategoryResponse> result = categoryService.listCategories(userId, false, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Active");
    }

    @Test
    void listCategories_nestsSubcategoriesUnderParents() {
        Category root = buildCategory(userId, null, "Root", false, false, false);
        Category sub = buildCategory(userId, root, "Sub", false, false, false);

        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(root, sub));

        List<CategoryResponse> result = categoryService.listCategories(userId, false, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Root");
        assertThat(result.get(0).subcategories()).hasSize(1);
        assertThat(result.get(0).subcategories().get(0).name()).isEqualTo("Sub");
    }

    // ── createCategory ────────────────────────────────────────────────────────

    @Test
    void createCategory_rootCategory_success() {
        when(categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNull(userId, null, "Groceries"))
                .thenReturn(false);
        when(categoryRepository.save(any())).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });

        CategoryResponse response = categoryService.createCategory(
                new CreateCategoryRequest("Groceries", null, "#FF5733", "shopping", 0), userId);

        assertThat(response.name()).isEqualTo("Groceries");
        assertThat(response.parentId()).isNull();
        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void createCategory_subcategory_success() {
        UUID parentId = UUID.randomUUID();
        Category parent = buildCategory(userId, null, "Food", false, false, false);
        ReflectionTestUtils.setField(parent, "id", parentId);

        when(categoryRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNull(userId, parentId, "Restaurants"))
                .thenReturn(false);
        when(categoryRepository.save(any())).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });

        CategoryResponse response = categoryService.createCategory(
                new CreateCategoryRequest("Restaurants", parentId, "#F39C12", "restaurant", 0), userId);

        assertThat(response.name()).isEqualTo("Restaurants");
        assertThat(response.parentId()).isEqualTo(parentId);
    }

    @Test
    void createCategory_thirdLevel_throws422() {
        UUID grandparentId = UUID.randomUUID();
        Category grandparent = buildCategory(userId, null, "Root", false, false, false);
        ReflectionTestUtils.setField(grandparent, "id", grandparentId);

        UUID parentId = UUID.randomUUID();
        Category parent = buildCategory(userId, grandparent, "Sub", false, false, false);
        ReflectionTestUtils.setField(parent, "id", parentId);

        when(categoryRepository.findById(parentId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("DeepSub", parentId, null, null, 0), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Maximum depth is 2");
    }

    @Test
    void createCategory_duplicateName_throws409() {
        when(categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNull(userId, null, "Food"))
                .thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("Food", null, null, null, 0), userId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createCategory_parentNotFound_throws404() {
        UUID parentId = UUID.randomUUID();
        when(categoryRepository.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("Sub", parentId, null, null, 0), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── editCategory ──────────────────────────────────────────────────────────

    @Test
    void editCategory_success() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(userId, null, "Old Name", false, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(cat));
        when(categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNullAndIdNot(userId, null, "New Name", catId))
                .thenReturn(false);
        when(categoryRepository.save(any())).thenReturn(cat);

        CategoryResponse response = categoryService.editCategory(catId,
                new EditCategoryRequest("New Name", "#00FF00", null, null), userId);

        assertThat(response.name()).isEqualTo("New Name");
    }

    @Test
    void editCategory_systemCategory_throws422() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(null, null, "Housing", true, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(cat));

        assertThatThrownBy(() -> categoryService.editCategory(catId,
                new EditCategoryRequest("NewName", null, null, null), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be edited");
    }

    @Test
    void editCategory_duplicateName_throws409() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(userId, null, "Old Name", false, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(cat));
        when(categoryRepository.existsByUserIdAndParentIdAndNameAndArchivedAtIsNullAndIdNot(userId, null, "Conflict Name", catId))
                .thenReturn(true);

        assertThatThrownBy(() -> categoryService.editCategory(catId,
                new EditCategoryRequest("Conflict Name", null, null, null), userId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void editCategory_notFound_throws404() {
        UUID catId = UUID.randomUUID();
        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.editCategory(catId,
                new EditCategoryRequest("Name", null, null, null), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── setHidden ─────────────────────────────────────────────────────────────

    @Test
    void setHidden_userCategory_success() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(userId, null, "My Category", false, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdVisibleToUser(catId, userId)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenReturn(cat);

        CategoryResponse response = categoryService.setHidden(catId, true, userId);

        assertThat(response.isHidden()).isTrue();
    }

    @Test
    void setHidden_systemCategory_success() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(null, null, "Housing", true, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdVisibleToUser(catId, userId)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenReturn(cat);

        CategoryResponse response = categoryService.setHidden(catId, true, userId);

        assertThat(response.isHidden()).isTrue();
    }

    @Test
    void setHidden_notFound_throws404() {
        UUID catId = UUID.randomUUID();
        when(categoryRepository.findByIdVisibleToUser(catId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.setHidden(catId, true, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── archiveCategory ───────────────────────────────────────────────────────

    @Test
    void archiveCategory_cascadesToSubcategories() {
        UUID catId = UUID.randomUUID();
        Category parent = buildCategory(userId, null, "Parent", false, false, false);
        ReflectionTestUtils.setField(parent, "id", catId);

        UUID subId = UUID.randomUUID();
        Category sub = buildCategory(userId, parent, "Sub", false, false, false);
        ReflectionTestUtils.setField(sub, "id", subId);

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(parent));
        when(categoryRepository.save(any())).thenReturn(parent);
        when(categoryRepository.findSubcategoriesByUserIdAndParentId(userId, catId)).thenReturn(List.of(sub));

        categoryService.archiveCategory(catId, userId);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        List<Category> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isArchived()).isTrue();
    }

    @Test
    void archiveCategory_systemCategory_throws422() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(null, null, "Housing", true, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(cat));

        assertThatThrownBy(() -> categoryService.archiveCategory(catId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot be archived");
    }

    @Test
    void archiveCategory_notFound_throws404() {
        UUID catId = UUID.randomUUID();
        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.archiveCategory(catId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void archiveCategory_noSubcategories_doesNotCallSaveAll() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(userId, null, "My Cat", false, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenReturn(cat);
        when(categoryRepository.findSubcategoriesByUserIdAndParentId(userId, catId)).thenReturn(List.of());

        categoryService.archiveCategory(catId, userId);

        verify(categoryRepository, never()).saveAll(any());
    }

    // ── unarchiveCategory ─────────────────────────────────────────────────────

    @Test
    void unarchiveCategory_success() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(userId, null, "My Cat", false, false, true);
        ReflectionTestUtils.setField(cat, "id", catId);
        ReflectionTestUtils.setField(cat, "archivedAt", Instant.now());

        when(categoryRepository.findByIdAndUserId(catId, userId)).thenReturn(Optional.of(cat));
        when(categoryRepository.save(any())).thenReturn(cat);

        CategoryResponse response = categoryService.unarchiveCategory(catId, userId);

        assertThat(response.isArchived()).isFalse();
    }

    // ── createRule ────────────────────────────────────────────────────────────

    @Test
    void createRule_success() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(null, null, "Food", true, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        when(categoryRepository.findById(catId)).thenReturn(Optional.of(cat));
        when(categoryRuleRepository.save(any())).thenAnswer(inv -> {
            CategoryRule r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });

        CategoryRuleResponse response = categoryService.createRule(
                new CreateCategoryRuleRequest("McDonald", catId, null, null, 0), userId);

        assertThat(response.pattern()).isEqualTo("McDonald");
        assertThat(response.categoryId()).isEqualTo(catId);
    }

    @Test
    void createRule_subcategoryNotUnderCategory_throws422() {
        UUID catId = UUID.randomUUID();
        UUID otherCatId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();

        Category cat = buildCategory(null, null, "Food", true, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        Category otherCat = buildCategory(null, null, "Transport", true, false, false);
        ReflectionTestUtils.setField(otherCat, "id", otherCatId);

        Category sub = buildCategory(null, otherCat, "Fuel", true, false, false);
        ReflectionTestUtils.setField(sub, "id", subId);

        when(categoryRepository.findById(catId)).thenReturn(Optional.of(cat));
        when(categoryRepository.findById(subId)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> categoryService.createRule(
                new CreateCategoryRuleRequest("Gas", catId, subId, null, 0), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void createRule_categoryNotFound_throws404() {
        UUID catId = UUID.randomUUID();
        when(categoryRepository.findById(catId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.createRule(
                new CreateCategoryRuleRequest("keyword", catId, null, null, 0), userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── listRules ─────────────────────────────────────────────────────────────

    @Test
    void listRules_returnsActiveRulesOrdered() {
        UUID catId = UUID.randomUUID();
        Category cat = buildCategory(null, null, "Food", true, false, false);
        ReflectionTestUtils.setField(cat, "id", catId);

        CategoryRule rule1 = buildRule(userId, "McDonalds", cat, 1);
        CategoryRule rule2 = buildRule(userId, "Netflix", cat, 0);

        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(rule2, rule1));

        List<CategoryRuleResponse> result = categoryService.listRules(userId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).pattern()).isEqualTo("Netflix");
        assertThat(result.get(1).pattern()).isEqualTo("McDonalds");
    }

    // ── deleteRule ────────────────────────────────────────────────────────────

    @Test
    void deleteRule_success() {
        UUID ruleId = UUID.randomUUID();
        Category cat = buildCategory(null, null, "Food", true, false, false);
        CategoryRule rule = buildRule(userId, "keyword", cat, 0);
        ReflectionTestUtils.setField(rule, "id", ruleId);

        when(categoryRuleRepository.findByIdAndUserId(ruleId, userId)).thenReturn(Optional.of(rule));

        categoryService.deleteRule(ruleId, userId);

        verify(categoryRuleRepository).delete(rule);
    }

    @Test
    void deleteRule_notFound_throws404() {
        UUID ruleId = UUID.randomUUID();
        when(categoryRuleRepository.findByIdAndUserId(ruleId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteRule(ruleId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Category buildCategory(UUID userId, Category parent, String name, boolean isDefault,
                                   boolean isHidden, boolean isArchived) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setUserId(userId);
        c.setParent(parent);
        c.setName(name);
        c.setDefault(isDefault);
        c.setHidden(isHidden);
        c.setArchived(isArchived);
        return c;
    }

    private CategoryRule buildRule(UUID userId, String pattern, Category category, int priority) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setUserId(userId);
        r.setPattern(pattern);
        r.setCategory(category);
        r.setPriority(priority);
        return r;
    }
}
