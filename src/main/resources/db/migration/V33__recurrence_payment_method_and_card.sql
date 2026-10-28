-- Recorrências passam a ter forma de pagamento e, quando no cartão, o cartão das ocorrências
-- (assinaturas no cartão entram na fatura em vez de sair direto da conta).
ALTER TABLE recurrence_rules
    ADD COLUMN payment_method_id UUID REFERENCES payment_methods(id),
    ADD COLUMN credit_card_id    UUID REFERENCES credit_cards(id);

-- Até aqui toda ocorrência era gerada como OTHER.
UPDATE recurrence_rules
SET payment_method_id = (SELECT id FROM payment_methods WHERE slug = 'OTHER');

ALTER TABLE recurrence_rules
    ALTER COLUMN payment_method_id SET NOT NULL;

CREATE INDEX idx_recurrence_rules_card ON recurrence_rules (credit_card_id);
