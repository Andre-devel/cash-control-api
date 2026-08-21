-- ============================================================
-- V28 — Edição de item de fatura e reabertura de quitação simples
--
-- Duas peças independentes, na mesma migração porque as duas sustentam a
-- tela de gerenciamento de faturas.
--
-- (1) invoice_items.original_description / merchant_key
--
-- O import de fatura já recebe a descrição como o arquivo trouxe
-- (FaturaImportCommitRow.originalDescription) e já a usa para o hash de
-- duplicidade e para MerchantAliasService.remember — só nunca a persistiu.
-- Sem guardá-la, editar um item já renomeado pela tela nova derivaria a
-- chave da própria edição, não do estabelecimento, e a memória de apelido
-- (V27) casaria errado no import seguinte: exatamente a armadilha que o
-- cabeçalho da V27 documenta para transactions.merchant_key.
--
-- merchant_key aqui segue o mesmo contrato de transactions.merchant_key
-- (V26): mesma normalização (MerchantKey.of, ver InvoiceItem.deriveMerchantKey),
-- mesma função merchant_key_of() para o backfill concordar com o Java (ver
-- MerchantKeyBackfillIntegrationTest), mesmo índice parcial. A função é
-- extraída aqui, em vez de repetir a expressão inline como a V26 fez,
-- porque agora há duas tabelas para backfillar com a mesma regra.
--
-- Itens antigos ficam com original_description NULL — não há como saber,
-- olhando o histórico, qual descrição foi editada de qual (mesma limitação
-- que a V27 já tem para merchant_aliases). deriveMerchantKey() cai para
-- description nesse caso, então o backfill roda sobre description mesmo.
--
-- (2) invoices.paid_without_transaction
--
-- payInvoice (pagamento real) e settle/alreadyPaid (quitação simples do
-- import) levam ao mesmo status PAID por caminhos diferentes: só o primeiro
-- cria uma Transaction de pagamento na conta. Reabrir uma fatura quitada
-- pelo segundo caminho é seguro (não sobra nada órfão); reabrir uma quitada
-- pelo primeiro deixaria a transação de pagamento solta, sem fatura para
-- justificá-la. A coluna marca qual dos dois caminhos foi.
--
-- merchant_key_of() é dropada no fim desta migração: é apenas o
-- instrumento do backfill, não parte do contrato de schema — quem manda
-- daqui em diante é InvoiceItem.deriveMerchantKey(), como já vale para
-- Transaction (V26).
--
-- O backfill casa por igualdade exata com a descrição que payInvoice monta
-- (CreditCardServiceImpl.payInvoice, determinística: "Invoice payment — "
-- || nome do cartão || " " || mês de referência). Falso negativo (marcar
-- TRUE uma fatura que teve pagamento real, por exemplo se o nome do cartão
-- mudou depois) só bloqueia a reabertura com a mensagem de "exclua a
-- transação antes" — o modo de falha seguro, então o backfill não tenta
-- ser mais esperto que isso.
-- ============================================================

CREATE FUNCTION merchant_key_of(description text) RETURNS text AS $$
    SELECT nullif(
        regexp_replace(
            rtrim(left(
                btrim(regexp_replace(
                    regexp_replace(
                        regexp_replace(
                            lower(unaccent(
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
            '(\s+(ac|al|ap|am|ba|ce|df|es|go|ma|mt|ms|mg|pa|pb|pr|pe|pi|rj|rn|rs|ro|rr|sc|sp|se|to|br|bra))+$',
            ''
        ),
        ''
    )
$$ LANGUAGE sql IMMUTABLE;

ALTER TABLE invoice_items ADD COLUMN original_description VARCHAR(255);
ALTER TABLE invoice_items ADD COLUMN merchant_key VARCHAR(64);

UPDATE invoice_items SET merchant_key = merchant_key_of(description);

CREATE INDEX idx_invoice_items_merchant
    ON invoice_items (user_id, merchant_key)
    WHERE merchant_key IS NOT NULL;

DROP FUNCTION merchant_key_of(text);

ALTER TABLE invoices ADD COLUMN paid_without_transaction BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE invoices i
SET paid_without_transaction = TRUE
WHERE i.status = 'PAID'
  AND NOT EXISTS (
      SELECT 1
      FROM transactions t
      JOIN credit_cards cc ON cc.id = i.credit_card_id
      WHERE t.user_id = i.user_id
        AND t.description = 'Invoice payment — ' || cc.name || ' ' || i.reference_month
  );
