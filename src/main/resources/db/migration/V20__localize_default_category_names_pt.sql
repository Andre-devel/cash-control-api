-- ============================================================
-- V20 — Localize default category names to Portuguese
--
-- V13 passou a semear as categorias de sistema em pt-BR, mas bases que já
-- tinham rodado a versão anterior do seed continuam com os nomes em inglês
-- (o Flyway não reaplica uma migração já executada). Esta migração renomeia
-- essas categorias, na mesma linha da V19 para as formas de pagamento.
--
-- Só toca em categorias de sistema (user_id IS NULL AND is_default = TRUE):
-- categorias criadas ou renomeadas por usuários têm user_id preenchido e
-- ficam intactas. Em bases novas, já em pt-BR, é um no-op.
--
-- O guard NOT EXISTS protege os índices únicos
-- (uidx_categories_system_root / uidx_categories_system_child) caso o nome
-- em português já exista no mesmo escopo.
-- ============================================================

-- ── Categorias raiz ─────────────────────────────────────────

UPDATE categories c
SET name = t.pt, updated_at = NOW()
FROM (VALUES
    ('Housing',        'Moradia'),
    ('Food',           'Alimentação'),
    ('Transport',      'Transporte'),
    ('Health',         'Saúde'),
    ('Education',      'Educação'),
    ('Entertainment',  'Lazer'),
    ('Clothing',       'Vestuário'),
    ('Personal Care',  'Cuidados Pessoais'),
    ('Subscriptions',  'Assinaturas'),
    ('Travel',         'Viagens'),
    ('Taxes & Fees',   'Impostos e Taxas'),
    ('Other Expenses', 'Outras Despesas'),
    ('Salary',         'Salário'),
    ('Investments',    'Investimentos'),
    ('Gifts',          'Presentes'),
    ('Other Income',   'Outras Receitas')
) AS t(en, pt)
WHERE c.user_id IS NULL
  AND c.parent_id IS NULL
  AND c.is_default = TRUE
  AND c.name = t.en
  AND NOT EXISTS (
      SELECT 1 FROM categories x
      WHERE x.user_id IS NULL AND x.parent_id IS NULL AND x.name = t.pt
  );

-- ── Subcategorias ───────────────────────────────────────────

UPDATE categories c
SET name = t.pt, updated_at = NOW()
FROM (VALUES
    ('Rent',                'Aluguel'),
    ('Condominium',         'Condomínio'),
    ('Electricity',         'Energia Elétrica'),
    ('Water',               'Água'),
    ('Groceries',           'Supermercado'),
    ('Restaurants',         'Restaurantes'),
    ('Fuel',                'Combustível'),
    ('Public Transit',      'Transporte Público'),
    ('Rideshare',           'Aplicativo (Uber/99)'),
    ('Vehicle Maintenance', 'Manutenção do Veículo'),
    ('Doctor',              'Médico'),
    ('Pharmacy',            'Farmácia'),
    ('Health Insurance',    'Plano de Saúde'),
    ('Gym',                 'Academia')
) AS t(en, pt)
WHERE c.user_id IS NULL
  AND c.parent_id IS NOT NULL
  AND c.is_default = TRUE
  AND c.name = t.en
  AND NOT EXISTS (
      SELECT 1 FROM categories x
      WHERE x.user_id IS NULL AND x.parent_id = c.parent_id AND x.name = t.pt
  );
