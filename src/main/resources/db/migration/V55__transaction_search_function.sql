-- ============================================================
-- V55 — Busca da lista de transações
-- transaction_matches_search(id, consulta) decide se um lançamento casa com o que o
-- usuário digitou no campo de busca da lista (GET /api/v1/transactions?searchText=):
--   * sem acento e sem diferenciar maiúsculas ("alimentacao" acha "Alimentação");
--   * palavra por palavra, em qualquer ordem: cada palavra precisa aparecer na descrição,
--     nas observações, na contraparte, na categoria, na subcategoria, na conta ou no cartão;
--   * também comparando sem espaços e símbolos ("mercado livre" acha "MERCADOLIVRE*MERCADOL");
--   * um valor em pt-BR ("45,99", "R$ 1.200") casa com o valor exato do lançamento.
-- unaccent é extensão "trusted" (PG 13+): o dono do banco a cria sem superusuário.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS unaccent;

-- Forma comparável de um texto: sem acento, minúsculo. IMMUTABLE com o dicionário fixo.
CREATE FUNCTION search_fold(value text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$ SELECT lower(public.unaccent('public.unaccent'::regdictionary, coalesce(value, ''))) $$;

CREATE FUNCTION transaction_matches_search(p_id uuid, p_query text) RETURNS boolean
    LANGUAGE sql STABLE AS
$$
WITH tx AS (
    SELECT t.amount,
           search_fold(concat_ws(' ', t.description, t.notes, t.counterparty_name,
                                 c.name, s.name, a.name, cc.name)) AS hay
    FROM transactions t
    LEFT JOIN categories c ON c.id = t.category_id
    LEFT JOIN categories s ON s.id = t.subcategory_id
    LEFT JOIN accounts a ON a.id = t.account_id
    LEFT JOIN credit_cards cc ON cc.id = t.credit_card_id
    WHERE t.id = p_id
),
query AS (
    SELECT CASE
               WHEN p_query ~ '^\s*(R\$\s*)?(\d{1,3}(\.\d{3})+|\d+)(,\d{1,2})?\s*$'
               THEN replace(replace(regexp_replace(p_query, '[^0-9,.]', '', 'g'), '.', ''), ',', '.')::numeric
           END AS amount
),
words AS (
    SELECT w FROM regexp_split_to_table(search_fold(p_query), '\s+') AS w WHERE w <> ''
)
SELECT coalesce((
    SELECT (query.amount IS NOT NULL AND abs(tx.amount) = query.amount)
           OR (EXISTS (SELECT 1 FROM words)
               AND NOT EXISTS (
                   SELECT 1 FROM words
                   WHERE position(w IN tx.hay) = 0
                     AND position(regexp_replace(w, '[^a-z0-9]', '', 'g')
                                  IN regexp_replace(tx.hay, '[^a-z0-9]', '', 'g')) = 0))
    FROM tx, query
), false)
$$;
