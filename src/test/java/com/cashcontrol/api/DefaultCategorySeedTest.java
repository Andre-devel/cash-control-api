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
class DefaultCategorySeedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void defaultExpenseRootCategoriesExist() {
        List<String> expenseCategories = jdbcTemplate.queryForList(
                "SELECT name FROM categories WHERE user_id IS NULL AND parent_id IS NULL AND is_default = TRUE ORDER BY name",
                String.class);

        assertThat(expenseCategories).contains(
                "Housing", "Food", "Transport", "Health", "Education",
                "Entertainment", "Clothing", "Personal Care", "Subscriptions",
                "Travel", "Taxes & Fees", "Other Expenses");
    }

    @Test
    void defaultIncomeRootCategoriesExist() {
        List<String> incomeCategories = jdbcTemplate.queryForList(
                "SELECT name FROM categories WHERE user_id IS NULL AND parent_id IS NULL AND is_default = TRUE ORDER BY name",
                String.class);

        assertThat(incomeCategories).contains(
                "Salary", "Freelance", "Investments", "Gifts", "Other Income");
    }

    @Test
    void housingSubcategoriesExist() {
        List<String> subcategories = jdbcTemplate.queryForList(
                "SELECT c.name FROM categories c "
                        + "JOIN categories p ON c.parent_id = p.id "
                        + "WHERE p.name = 'Housing' AND p.user_id IS NULL AND c.user_id IS NULL",
                String.class);

        assertThat(subcategories).contains("Rent", "Condominium", "Electricity", "Water", "Internet");
    }

    @Test
    void allDefaultCategoriesHaveUserIdNull() {
        Long nonNullUserCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM categories WHERE is_default = TRUE AND user_id IS NOT NULL",
                Long.class);
        assertThat(nonNullUserCount).isZero();
    }

    @Test
    void allDefaultCategoriesAreNotHiddenByDefault() {
        Long hiddenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM categories WHERE is_default = TRUE AND is_hidden = TRUE",
                Long.class);
        assertThat(hiddenCount).isZero();
    }

    @Test
    void minimumRootCategoryCountMet() {
        Long rootCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM categories WHERE user_id IS NULL AND parent_id IS NULL AND is_default = TRUE",
                Long.class);
        assertThat(rootCount)
                .as("Expected at least 17 default root categories (12 expense + 5 income)")
                .isGreaterThanOrEqualTo(17L);
    }

    @Test
    void seedIsIdempotentNoDuplicateSystemRootCategories() {
        List<String> duplicates = jdbcTemplate.queryForList(
                "SELECT name FROM categories "
                        + "WHERE user_id IS NULL AND parent_id IS NULL "
                        + "GROUP BY name HAVING COUNT(*) > 1",
                String.class);
        assertThat(duplicates)
                .as("Duplicate system root categories found: %s", duplicates)
                .isEmpty();
    }
}
