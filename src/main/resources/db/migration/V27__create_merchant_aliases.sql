-- ============================================================
-- V27 — Apelido de estabelecimento
--
-- Guarda como o usuário prefere ver cada estabelecimento: a fatura traz
-- "ANTHROPIC* CLAUDE SUB" todo mês e ele renomeia para "Claude - mensalidade"
-- toda vez. Aqui essa renomeação é lembrada e volta pré-preenchida na prévia
-- do import seguinte, ao lado da categoria que já vinha da memória de
-- estabelecimento (V26).
--
-- ATENÇÃO à diferença para transactions.merchant_key, que tem o mesmo nome e
-- NÃO tem o mesmo conteúdo: lá a chave é derivada da descrição *já editada*
-- (Transaction.deriveMerchantKey roda no @PrePersist, depois de o usuário ter
-- renomeado a linha), então a chave de um lançamento renomeado é a do apelido.
-- Aqui a chave é sempre derivada da descrição *como o arquivo a trouxe* — é o
-- único texto que se repete de um mês para o outro, e por isso o único que
-- serve de identidade para procurar o apelido. Inferir o apelido a partir de
-- transactions seria circular.
--
-- Uma linha por estabelecimento renomeado, por usuário: a tabela é pequena o
-- bastante para ser lida inteira e indexada em memória a cada importação, o que
-- dispensa a busca por regex que CategorySuggester precisa fazer sobre
-- transactions.
--
-- Sem backfill: não há como saber, olhando o histórico, qual descrição foi
-- renomeada de quê. A memória começa vazia e aprende na próxima importação.
-- ============================================================

CREATE TABLE merchant_aliases (
    id           UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users(id),
    merchant_key VARCHAR(64)  NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Leitura é sempre "todos os apelidos deste usuário"; a unicidade por chave é o
-- que faz a renomeação mais recente substituir a anterior em vez de acumular.
CREATE UNIQUE INDEX uidx_merchant_aliases_user_key ON merchant_aliases (user_id, merchant_key);
