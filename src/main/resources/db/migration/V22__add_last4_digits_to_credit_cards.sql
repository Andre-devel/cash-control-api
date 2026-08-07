-- ============================================================
-- V22 — Últimos 4 dígitos do cartão
--
-- A fatura em PDF vem dividida em seções "CARTÃO 2306****7866", uma por
-- cartão (titular e adicionais). Sem os 4 dígitos finais gravados aqui, a
-- importação não teria como dizer qual seção pertence a qual cartão e o
-- usuário teria de escolher tudo à mão.
--
-- Nullable de propósito: cartões já cadastrados continuam válidos sem o
-- campo — quem quiser o match automático edita o cartão e preenche.
-- Não é único: dois cartões do mesmo usuário podem terminar igual, e nesse
-- caso a sugestão simplesmente não é feita.
-- ============================================================

ALTER TABLE credit_cards ADD COLUMN last4_digits VARCHAR(4);
