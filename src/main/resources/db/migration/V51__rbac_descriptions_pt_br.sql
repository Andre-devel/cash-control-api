-- ============================================================
-- V51 — Descrições em pt-BR dos papéis e permissões de sistema
-- Só toca as linhas semeadas (V7 e V44), localizadas por name e
-- pela flag de sistema; papéis e permissões criados pelo usuário não mudam.
-- ============================================================

UPDATE roles r
SET description = v.description,
    updated_at  = NOW()
FROM (VALUES
    ('ADMIN', 'Administrador do sistema, com todas as permissões nativas.'),
    ('USER',  'Usuário autenticado padrão, sem permissões nativas.')
) AS v (role_name, description)
WHERE r.name = v.role_name
  AND r.is_system_role = TRUE;

UPDATE permissions p
SET description = v.description,
    updated_at  = NOW()
FROM (VALUES
    ('user:create',       'Criar contas de usuário.'),
    ('user:read',         'Consultar dados das contas de usuário.'),
    ('user:update',       'Alterar dados e status das contas de usuário.'),
    ('user:delete',       'Excluir contas de usuário (exclusão lógica).'),
    ('role:create',       'Criar papéis.'),
    ('role:update',       'Alterar descrição e permissões dos papéis.'),
    ('role:delete',       'Excluir papéis que não estejam atribuídos a nenhum usuário.'),
    ('permission:grant',  'Conceder permissões a papéis ou diretamente a usuários.'),
    ('permission:revoke', 'Revogar permissões de papéis ou diretamente de usuários.'),
    ('audit:view',        'Acessar o log de auditoria e os relatórios de segurança.'),
    ('auth:manage',       'Gerenciar a segurança da autenticação: bloqueios e reautenticação forçada.'),
    ('bank-sync:view',    'Ver o histórico de sincronização bancária das próprias conexões.')
) AS v (perm_name, description)
WHERE p.name = v.perm_name
  AND p.is_system_perm = TRUE;
