-- ============================================================
-- V13 — Default Category Seed Data
-- System-level categories (user_id IS NULL, is_default = TRUE).
-- Idempotent: uses WHERE NOT EXISTS guards.
-- ============================================================

DO $$
DECLARE
    -- Expense root categories
    housing_id       UUID;
    food_id          UUID;
    transport_id     UUID;
    health_id        UUID;
    education_id     UUID;
    entertainment_id UUID;
    clothing_id      UUID;
    personal_care_id UUID;
    subscriptions_id UUID;
    travel_id        UUID;
    taxes_id         UUID;
    other_expenses_id UUID;

    -- Income root categories
    salary_id        UUID;
    freelance_id     UUID;
    investments_id   UUID;
    gifts_id         UUID;
    other_income_id  UUID;

BEGIN
    -- ── Expense Root Categories ─────────────────────────────

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Moradia', '#E74C3C', 'home', TRUE, 1
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Moradia' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO housing_id;

    IF housing_id IS NULL THEN
        SELECT id INTO housing_id FROM categories WHERE name = 'Moradia' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Alimentação', '#F39C12', 'restaurant', TRUE, 2
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Alimentação' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO food_id;

    IF food_id IS NULL THEN
        SELECT id INTO food_id FROM categories WHERE name = 'Alimentação' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Transporte', '#3498DB', 'directions_car', TRUE, 3
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Transporte' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO transport_id;

    IF transport_id IS NULL THEN
        SELECT id INTO transport_id FROM categories WHERE name = 'Transporte' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Saúde', '#2ECC71', 'local_hospital', TRUE, 4
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Saúde' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO health_id;

    IF health_id IS NULL THEN
        SELECT id INTO health_id FROM categories WHERE name = 'Saúde' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Educação', '#9B59B6', 'school', TRUE, 5
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Educação' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO education_id;

    IF education_id IS NULL THEN
        SELECT id INTO education_id FROM categories WHERE name = 'Educação' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Lazer', '#E91E63', 'movie', TRUE, 6
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Lazer' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO entertainment_id;

    IF entertainment_id IS NULL THEN
        SELECT id INTO entertainment_id FROM categories WHERE name = 'Lazer' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Vestuário', '#00BCD4', 'checkroom', TRUE, 7
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Vestuário' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO clothing_id;

    IF clothing_id IS NULL THEN
        SELECT id INTO clothing_id FROM categories WHERE name = 'Vestuário' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Cuidados Pessoais', '#FF9800', 'spa', TRUE, 8
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Cuidados Pessoais' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO personal_care_id;

    IF personal_care_id IS NULL THEN
        SELECT id INTO personal_care_id FROM categories WHERE name = 'Cuidados Pessoais' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Assinaturas', '#795548', 'subscriptions', TRUE, 9
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Assinaturas' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO subscriptions_id;

    IF subscriptions_id IS NULL THEN
        SELECT id INTO subscriptions_id FROM categories WHERE name = 'Assinaturas' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Viagens', '#009688', 'flight', TRUE, 10
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Viagens' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO travel_id;

    IF travel_id IS NULL THEN
        SELECT id INTO travel_id FROM categories WHERE name = 'Viagens' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Impostos e Taxas', '#607D8B', 'account_balance', TRUE, 11
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Impostos e Taxas' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO taxes_id;

    IF taxes_id IS NULL THEN
        SELECT id INTO taxes_id FROM categories WHERE name = 'Impostos e Taxas' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Outras Despesas', '#9E9E9E', 'more_horiz', TRUE, 12
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Outras Despesas' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO other_expenses_id;

    IF other_expenses_id IS NULL THEN
        SELECT id INTO other_expenses_id FROM categories WHERE name = 'Outras Despesas' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    -- ── Income Root Categories ──────────────────────────────

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Salário', '#27AE60', 'work', TRUE, 13
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Salário' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO salary_id;

    IF salary_id IS NULL THEN
        SELECT id INTO salary_id FROM categories WHERE name = 'Salário' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Freelance', '#2ECC71', 'computer', TRUE, 14
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Freelance' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO freelance_id;

    IF freelance_id IS NULL THEN
        SELECT id INTO freelance_id FROM categories WHERE name = 'Freelance' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Investimentos', '#1ABC9C', 'trending_up', TRUE, 15
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Investimentos' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO investments_id;

    IF investments_id IS NULL THEN
        SELECT id INTO investments_id FROM categories WHERE name = 'Investimentos' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Presentes', '#F1C40F', 'card_giftcard', TRUE, 16
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Presentes' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO gifts_id;

    IF gifts_id IS NULL THEN
        SELECT id INTO gifts_id FROM categories WHERE name = 'Presentes' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    INSERT INTO categories (name, color, icon, is_default, sort_order)
    SELECT 'Outras Receitas', '#BDC3C7', 'attach_money', TRUE, 17
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = 'Outras Receitas' AND user_id IS NULL AND parent_id IS NULL)
    RETURNING id INTO other_income_id;

    IF other_income_id IS NULL THEN
        SELECT id INTO other_income_id FROM categories WHERE name = 'Outras Receitas' AND user_id IS NULL AND parent_id IS NULL;
    END IF;

    -- ── Subcategorias de Moradia ─────────────────────────────

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT housing_id, 'Aluguel', '#E74C3C', 'home', TRUE, 1
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = housing_id AND name = 'Aluguel' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT housing_id, 'Condomínio', '#E74C3C', 'apartment', TRUE, 2
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = housing_id AND name = 'Condomínio' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT housing_id, 'Energia Elétrica', '#E74C3C', 'bolt', TRUE, 3
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = housing_id AND name = 'Energia Elétrica' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT housing_id, 'Água', '#E74C3C', 'water_drop', TRUE, 4
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = housing_id AND name = 'Água' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT housing_id, 'Internet', '#E74C3C', 'wifi', TRUE, 5
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = housing_id AND name = 'Internet' AND user_id IS NULL);

    -- ── Subcategorias de Alimentação ─────────────────────────

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT food_id, 'Supermercado', '#F39C12', 'local_grocery_store', TRUE, 1
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = food_id AND name = 'Supermercado' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT food_id, 'Restaurantes', '#F39C12', 'restaurant_menu', TRUE, 2
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = food_id AND name = 'Restaurantes' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT food_id, 'Delivery', '#F39C12', 'delivery_dining', TRUE, 3
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = food_id AND name = 'Delivery' AND user_id IS NULL);

    -- ── Subcategorias de Transporte ──────────────────────────

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT transport_id, 'Combustível', '#3498DB', 'local_gas_station', TRUE, 1
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = transport_id AND name = 'Combustível' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT transport_id, 'Transporte Público', '#3498DB', 'directions_bus', TRUE, 2
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = transport_id AND name = 'Transporte Público' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT transport_id, 'Aplicativo (Uber/99)', '#3498DB', 'local_taxi', TRUE, 3
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = transport_id AND name = 'Aplicativo (Uber/99)' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT transport_id, 'Manutenção do Veículo', '#3498DB', 'build', TRUE, 4
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = transport_id AND name = 'Manutenção do Veículo' AND user_id IS NULL);

    -- ── Subcategorias de Saúde ───────────────────────────────

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT health_id, 'Médico', '#2ECC71', 'medical_services', TRUE, 1
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = health_id AND name = 'Médico' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT health_id, 'Farmácia', '#2ECC71', 'local_pharmacy', TRUE, 2
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = health_id AND name = 'Farmácia' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT health_id, 'Plano de Saúde', '#2ECC71', 'health_and_safety', TRUE, 3
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = health_id AND name = 'Plano de Saúde' AND user_id IS NULL);

    INSERT INTO categories (parent_id, name, color, icon, is_default, sort_order)
    SELECT health_id, 'Academia', '#2ECC71', 'fitness_center', TRUE, 4
    WHERE NOT EXISTS (SELECT 1 FROM categories WHERE parent_id = health_id AND name = 'Academia' AND user_id IS NULL);

END $$;
