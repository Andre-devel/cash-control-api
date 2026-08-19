-- ============================================================
-- V26 — Chave de estabelecimento para memória de categorização
--
-- merchant_key reduz a descrição de um lançamento à identidade do
-- comerciante (ver com.cashcontrol.api.service.MerchantKey), para que a
-- sugestão de categoria no import de fatura possa agrupar o histórico do
-- usuário por estabelecimento em vez de depender só de regras cadastradas
-- à mão. Não existe "aprender" como passo separado: gravar a transação
-- passa a ser aprender, porque toda escrita deriva a chave da descrição
-- (ver Transaction.deriveMerchantKey, @PrePersist/@PreUpdate).
--
-- O backfill abaixo repete em SQL puro, passo a passo, a mesma
-- normalização de MerchantKey.of() — os dois precisam concordar, senão o
-- histórico antigo fica com chave errada e a memória fica silenciosamente
-- furada (ver MerchantKeyBackfillIntegrationTest, que trava essa
-- equivalência). É best-effort sobre o que já está no banco; daqui em
-- diante quem manda é o callback da entidade.
--
-- O índice é parcial pelo mesmo motivo do uidx_transactions_external_ref
-- da V21: leitura é sempre (user_id, merchant_key), linhas sem chave
-- nunca são consultadas por ele.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS unaccent;

ALTER TABLE transactions ADD COLUMN merchant_key VARCHAR(64);

UPDATE transactions
SET merchant_key = nullif(
    regexp_replace(
        -- 7. tamanho: cortar antes do sufixo de praça, e não depois, para
        -- que uma chave já truncada passe pelas mesmas regras que uma
        -- curta (of(of(x)) == of(x) em MerchantKey.of()).
        rtrim(left(
            -- 6. resto não alfanumérico vira espaço simples; sobra só
            -- [a-z0-9] e espaço, o que torna a normalização reproduzível
            -- nas duas implementações.
            btrim(regexp_replace(
                -- 5. sequência de 3+ dígitos (id de pedido, cashback) some.
                regexp_replace(
                    -- 4. prefixo de gateway de pagamento antes do "*".
                    regexp_replace(
                        -- 2-3. acentos e caixa.
                        lower(unaccent(
                            -- 1. sufixo "(Parcela X de Y)".
                            btrim(regexp_replace(
                                description,
                                '\(\s*parcela\s+\d+\s+de\s+\d+\s*\)',
                                '',
                                'gi'
                            ))
                        )),
                        '^[a-z0-9]{1,9}[\s ]*\*[\s ]*',
                        ''
                    ),
                    '[0-9]{3,}',
                    ' ',
                    'g'
                ),
                '[^a-z0-9]+',
                ' ',
                'g'
            )),
        64)),
        -- 8. sufixo de praça (UF, BR, BRA) que o emissor põe e tira sem
        -- critério de um mês para o outro.
        '(\s+(ac|al|ap|am|ba|ce|df|es|go|ma|mt|ms|mg|pa|pb|pr|pe|pi|rj|rn|rs|ro|rr|sc|sp|se|to|br|bra))+$',
        ''
    ),
    ''
);

CREATE INDEX idx_transactions_merchant
    ON transactions (user_id, merchant_key)
    WHERE merchant_key IS NOT NULL;
