package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CategoriesSchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── categories ────────────────────────────────────────────

    @Test
    void categoriesTableExists() {
        assertTableExists("categories");
    }

    @Test
    void categoriesTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'categories'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "parent_id", "name", "color", "icon",
                "sort_order", "is_default", "is_hidden", "is_archived",
                "archived_at", "created_at", "updated_at");
    }

    @Test
    void categoriesUserIndex_exists() {
        assertIndexExists("categories", "idx_categories_user");
    }

    @Test
    void categoriesParentIndex_exists() {
        assertIndexExists("categories", "idx_categories_parent");
    }

    @Test
    void categoriesSystemRootUniqueIndex_exists() {
        assertIndexExists("categories", "uidx_categories_system_root");
    }

    @Test
    void categoriesSystemChildUniqueIndex_exists() {
        assertIndexExists("categories", "uidx_categories_system_child");
    }

    @Test
    void categoriesUserNameUniqueIndex_exists() {
        assertIndexExists("categories", "uidx_categories_user_name");
    }

    @Test
    void categoriesSelfReferentialFkExists() {
        Long fkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON tc.constraint_name = kcu.constraint_name "
                        + "JOIN information_schema.referential_constraints rc "
                        + "  ON tc.constraint_name = rc.constraint_name "
                        + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                        + "  AND tc.table_name = 'categories' "
                        + "  AND kcu.column_name = 'parent_id'",
                Long.class);
        assertThat(fkCount).as("categories.parent_id should have a FK to categories.id").isGreaterThan(0);
    }

    // ── tags ──────────────────────────────────────────────────

    @Test
    void tagsTableExists() {
        assertTableExists("tags");
    }

    @Test
    void tagsUniqueUserNameIndex_exists() {
        assertIndexExists("tags", "uidx_tags_user_name");
    }

    @Test
    void tagsUserIndex_exists() {
        assertIndexExists("tags", "idx_tags_user");
    }

    // ── category_rules ────────────────────────────────────────

    @Test
    void categoryRulesTableExists() {
        assertTableExists("category_rules");
    }

    @Test
    void categoryRulesTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'category_rules'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "pattern", "category_id", "subcategory_id",
                "account_id", "priority", "is_active", "created_at", "updated_at");
    }

    @Test
    void categoryRulesActivePriorityIndex_exists() {
        assertIndexExists("category_rules", "idx_category_rules_active_priority");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertTableExists(String tableName) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?)",
                Boolean.class, tableName);
        assertThat(exists).as("Table '%s' should exist", tableName).isTrue();
    }

    private void assertIndexExists(String tableName, String indexName) {
        List<String> found = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ? AND indexname = ?",
                String.class, tableName, indexName);
        assertThat(found)
                .as("Expected index '%s' on table '%s'", indexName, tableName)
                .hasSize(1);
    }
}
