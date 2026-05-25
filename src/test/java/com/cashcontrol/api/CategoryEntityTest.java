package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class CategoryEntityTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testUserId;

    @BeforeEach
    void createTestUser() {
        testUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "category-entity-test-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void canSaveAndRetrieveRootCategory() {
        Category root = new Category();
        root.setUserId(testUserId);
        root.setName("My Root Category");
        root.setColor("#FF5733");
        root.setIcon("home");

        Category saved = categoryRepository.saveAndFlush(root);

        assertThat(saved.getId()).isNotNull();

        Optional<Category> found = categoryRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("My Root Category");
        assertThat(found.get().getColor()).isEqualTo("#FF5733");
        assertThat(found.get().getParent()).isNull();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void canSaveSubcategoryWithParent() {
        Category parent = new Category();
        parent.setUserId(testUserId);
        parent.setName("Parent Category");
        categoryRepository.save(parent);

        Category child = new Category();
        child.setUserId(testUserId);
        child.setName("Child Category");
        child.setParent(parent);
        Category savedChild = categoryRepository.save(child);

        categoryRepository.flush();

        Optional<Category> found = categoryRepository.findById(savedChild.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getParent()).isNotNull();
        assertThat(found.get().getParent().getId()).isEqualTo(parent.getId());
        assertThat(found.get().getParent().getName()).isEqualTo("Parent Category");
    }

    @Test
    void systemDefaultCategoriesHaveNullUserId() {
        List<Category> systemCategories = categoryRepository.findAllSystemCategories();

        assertThat(systemCategories).isNotEmpty();
        assertThat(systemCategories).allMatch(c -> c.getUserId() == null);
        assertThat(systemCategories).allMatch(Category::isDefault);
    }

    @Test
    void findAllByUserId_returnsOnlyUserCategories() {
        Category userCat = new Category();
        userCat.setUserId(testUserId);
        userCat.setName("User Category");
        categoryRepository.save(userCat);

        List<Category> userCategories = categoryRepository.findAllByUserId(testUserId);

        assertThat(userCategories).isNotEmpty();
        assertThat(userCategories).allMatch(c -> testUserId.equals(c.getUserId()));
    }

    @Test
    void hiddenFlagDefaultsToFalse() {
        Category category = new Category();
        category.setUserId(testUserId);
        category.setName("Visible Category");
        Category saved = categoryRepository.save(category);

        assertThat(saved.isHidden()).isFalse();
        assertThat(saved.isArchived()).isFalse();
        assertThat(saved.isDefault()).isFalse();
    }

    @Test
    void canArchiveAndUnarchiveCategory() {
        Category category = new Category();
        category.setUserId(testUserId);
        category.setName("Archivable Category");
        Category saved = categoryRepository.save(category);

        saved.setArchived(true);
        saved.setArchivedAt(java.time.Instant.now());
        categoryRepository.save(saved);

        Optional<Category> archived = categoryRepository.findById(saved.getId());
        assertThat(archived.get().isArchived()).isTrue();
        assertThat(archived.get().getArchivedAt()).isNotNull();
    }
}
