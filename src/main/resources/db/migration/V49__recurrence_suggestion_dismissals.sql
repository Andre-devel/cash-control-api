-- Sugestões de recorrência recusadas pelo usuário. pattern é a descrição normalizada
-- (RecurrenceDetector.normalize) do grupo de lançamentos sugerido.
CREATE TABLE recurrence_suggestion_dismissals (
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pattern      VARCHAR(255) NOT NULL,
    dismissed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, pattern)
);
