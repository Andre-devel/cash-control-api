# Database Schema — java-auth-template

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security 7 · PostgreSQL 18 · Flyway · JPA/Hibernate  
**Format:** [DBML](https://dbml.dbdiagram.io/) — paste into [dbdiagram.io](https://dbdiagram.io/) to render  
**Design principles:**
- UUID primary keys (`gen_random_uuid()`) on all entities
- `timestamptz` for all timestamps (UTC-aware)
- No database ENUM types — lookup tables for all categorical values
- Hashed storage for all security tokens (raw values never persisted)
- Soft-delete on `users` (`deleted_at`) for LGPD right-to-erasure
- Append-only `audit_logs` — no update or delete endpoint
- LGPD-aligned: masked IPs, masked emails, minimal PII
- Schema managed exclusively via Flyway versioned migrations

---

## Schema (DBML)

```dbml
// ============================================================
// Database Schema — java-auth-template
// ============================================================
// Stack   : Java 25 · Spring Boot 4.0.6 · Spring Security 7
//           PostgreSQL 18 · Flyway · JPA/Hibernate
// ============================================================
//
// Sections:
//   1. Lookup Tables         — categorical values (no ENUMs)
//   2. Core Identity         — users
//   3. RBAC                  — roles, permissions, join tables
//   4. Token Management      — email verification, password reset tokens
//   5. OAuth2                — provider accounts
//   6. Brute Force & Lockout — login attempts, account lockouts
//   7. Audit                 — append-only event log
//   8. Privacy & LGPD        — consent tracking
//   9. MFA Readiness         — future-proof placeholder
// ============================================================

// ============================================================
// SECTION 1 — LOOKUP TABLES
// All categorical values are data-driven lookup rows, never
// database ENUMs or bare string columns.
// Standard shape: id · name · slug · description · is_active
//                 created_at · updated_at
// Seeded by Flyway baseline migration; extended via API.
// ============================================================

Table account_statuses {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_account_statuses_slug"]
  }

  Note: "Seed values: ACTIVE, INACTIVE, LOCKED, PENDING_VERIFICATION. Controls authentication eligibility at every login check."
}

Table auth_origins {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_auth_origins_slug"]
  }

  Note: "Seed values: LOCAL, GOOGLE, MIXED. Tracks how the account was created and which login methods are available."
}

Table oauth_providers {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_oauth_providers_slug"]
  }

  Note: "Seed values: GOOGLE. Row-per-provider design allows adding GITHUB, MICROSOFT, APPLE without schema changes."
}

Table lockout_types {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_lockout_types_slug"]
  }

  Note: "Seed values: AUTOMATIC (threshold-based, has expires_at), MANUAL (admin-applied, permanent until explicitly unlocked)."
}

Table permission_categories {
  id          uuid         [pk, default: `gen_random_uuid()`]
  name        varchar(100) [not null, unique]
  slug        varchar(100) [not null, unique]
  description text
  is_active   boolean      [not null, default: true]
  created_at  timestamptz  [not null, default: `now()`]
  updated_at  timestamptz  [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_permission_categories_slug"]
  }

  Note: "Seed values: USER_MANAGEMENT, ROLE_MANAGEMENT, PERMISSION_MANAGEMENT, AUDIT, AUTH_MANAGEMENT. Groups permissions for admin UI."
}

Table authentication_methods {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(50) [not null, unique]
  slug        varchar(50) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_authentication_methods_slug"]
  }

  Note: "Seed values: PASSWORD, GOOGLE_OAUTH2, MFA_TOTP (reserved for future). Stored on login_attempts to record how the authentication was attempted."
}

Table audit_event_types {
  id          uuid         [pk, default: `gen_random_uuid()`]
  name        varchar(100) [not null, unique]
  slug        varchar(100) [not null, unique]
  description text
  category    varchar(50)  [not null, note: "AUTHENTICATION | AUTHORIZATION | ACCOUNT | TOKEN | SECURITY | PRIVACY"]
  severity    varchar(20)  [not null, default: "NORMAL", note: "NORMAL | HIGH | CRITICAL — used for alerting thresholds and SIEM integration"]
  is_active   boolean      [not null, default: true]
  created_at  timestamptz  [not null, default: `now()`]
  updated_at  timestamptz  [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_audit_event_types_slug"]
    (category, severity) [name: "idx_audit_event_types_category_severity"]
  }

  Note: "Seed values — AUTHENTICATION: USER_REGISTERED, USER_REGISTERED_GOOGLE, ACCOUNT_LINKED_GOOGLE, AUTH_SUCCESS, AUTH_FAILURE, AUTH_LOGOUT, EMAIL_VERIFIED. ACCOUNT: ACCOUNT_LOCKED, ACCOUNT_UNLOCKED, USER_CREATED, USER_DISABLED, USER_ACTIVATED, USER_DELETED, PASSWORD_CHANGED, PASSWORD_RESET_REQUESTED, PASSWORD_RESET_COMPLETED, CONSENT_ACCEPTED, PROVIDER_UNLINKED. TOKEN/HIGH: CREDENTIALS_INVALIDATED (covers password change, password reset, account disable, admin-triggered forced re-auth). AUTHORIZATION: ROLE_ASSIGNED, ROLE_REMOVED, ROLE_CREATED, PERMISSION_GRANTED, PERMISSION_REVOKED."
}

Table audit_outcomes {
  id          uuid        [pk, default: `gen_random_uuid()`]
  name        varchar(20) [not null, unique]
  slug        varchar(20) [not null, unique]
  description text
  is_active   boolean     [not null, default: true]
  created_at  timestamptz [not null, default: `now()`]
  updated_at  timestamptz [not null, default: `now()`]

  indexes {
    slug [unique, name: "uidx_audit_outcomes_slug"]
  }

  Note: "Seed values: SUCCESS, FAILURE."
}

// ============================================================
// SECTION 2 — CORE IDENTITY
// ============================================================

Table users {
  id                    uuid         [pk, default: `gen_random_uuid()`]
  email                 varchar(255) [not null, unique, note: "Stored plaintext for lookups. Must be masked in all logs and API responses (a***@example.com)."]
  password_hash         varchar(255) [note: "Argon2id (preferred) or BCrypt cost >= 12. NULL for GOOGLE-only accounts with no local password."]
  display_name          varchar(100) [note: "Optional. From OAuth2 profile or user self-update. Never included in JWT payload."]
  account_status_id     uuid         [not null, ref: > account_statuses.id]
  auth_origin_id        uuid         [not null, ref: > auth_origins.id]
  email_verified_at     timestamptz  [note: "NULL until email_verification_tokens token is consumed. Auto-set on Google OAuth2 registration."]
  failed_login_attempts int          [not null, default: 0, note: "Incremented on every auth failure. Reset to 0 on successful login."]
  lockout_expires_at    timestamptz  [note: "NULL when not locked or for permanent admin lockouts. Populated by automatic threshold lockouts."]
  lockout_type_id       uuid         [ref: > lockout_types.id, note: "NULL when account is not locked."]
  lockout_reason        text         [note: "Human-readable reason. Mandatory for MANUAL lockouts; generated for AUTOMATIC."]
  last_login_at         timestamptz
  credentials_updated_at timestamptz [not null, default: `now()`, note: "Updated on: password change, password reset, account disable, admin-triggered forced re-authentication. JWT iat claim is validated against this timestamp at token validation time — any token issued before this value is rejected as invalid. Enables lightweight stateless JWT invalidation without session persistence."]
  consent_accepted_at   timestamptz  [note: "LGPD: timestamp of explicit data processing consent at registration. Required field."]
  consent_version       varchar(20)  [note: "LGPD: version of the consent document accepted (e.g. 1.0). Enables future re-consent workflows."]
  deleted_at            timestamptz  [note: "Soft-delete anchor. Non-null = logically deleted. Checked before any authentication operation."]
  anonymized_at         timestamptz  [note: "LGPD erasure pipeline marker. Set when PII fields (email, display_name, password_hash) are zeroed. UUID and audit FK refs preserved."]
  created_at            timestamptz  [not null, default: `now()`]
  updated_at            timestamptz  [not null, default: `now()`]

  indexes {
    email [unique, name: "uidx_users_email"]
    account_status_id [name: "idx_users_account_status"]
    auth_origin_id [name: "idx_users_auth_origin"]
    (email, deleted_at) [name: "idx_users_email_deleted", note: "Login path lookup: WHERE deleted_at IS NULL"]
    (account_status_id, deleted_at) [name: "idx_users_status_deleted", note: "Admin list: filter active users by status"]
    last_login_at [name: "idx_users_last_login"]
    deleted_at [name: "idx_users_deleted_at"]
  }

  Note: "Core identity record. password_hash is write-only by convention — never returned in DTOs or emitted in logs. credentials_updated_at is the lightweight JWT invalidation anchor: any token with iat < credentials_updated_at is rejected, enabling forced re-authentication without session persistence. Lockout state is denormalized here for fast login-path reads; full history is in account_lockouts. Soft-delete (deleted_at) preserves audit trail integrity: audit_logs.actor_user_id and target_user_id FKs remain valid post-deletion. LGPD anonymization pipeline zeroes PII fields while retaining the UUID row for referential integrity."
}

// ============================================================
// SECTION 3 — RBAC: ROLES & PERMISSIONS
// ============================================================

Table roles {
  id             uuid         [pk, default: `gen_random_uuid()`]
  name           varchar(100) [not null, unique, note: "Immutable after creation. Used verbatim in @PreAuthorize authority strings and JWT claims."]
  description    text
  is_system_role boolean      [not null, default: false, note: "System roles (e.g. ADMIN seed) are protected from API deletion."]
  is_active      boolean      [not null, default: true]
  created_by_id  uuid         [ref: > users.id, note: "Admin who created this role. NULL for Flyway-seeded system roles."]
  created_at     timestamptz  [not null, default: `now()`]
  updated_at     timestamptz  [not null, default: `now()`]

  indexes {
    name [unique, name: "uidx_roles_name"]
    is_active [name: "idx_roles_is_active"]
  }

  Note: "Role names are immutable — renaming would break @PreAuthorize expressions and invalidate existing JWT authority claims without a coordinated migration. Description is the only mutable field."
}

Table permissions {
  id             uuid         [pk, default: `gen_random_uuid()`]
  name           varchar(100) [not null, unique, note: "Immutable. resource:action convention. e.g. user:create, role:update, audit:view, auth:manage"]
  description    text
  category_id    uuid         [ref: > permission_categories.id]
  is_system_perm boolean      [not null, default: false, note: "System permissions are protected from API deletion."]
  is_active      boolean      [not null, default: true]
  created_at     timestamptz  [not null, default: `now()`]
  updated_at     timestamptz  [not null, default: `now()`]

  indexes {
    name [unique, name: "uidx_permissions_name"]
    category_id [name: "idx_permissions_category"]
    is_active [name: "idx_permissions_is_active"]
  }

  Note: "Permission names are the authority strings that appear verbatim in JWT authorities claims and @PreAuthorize annotations. Immutable after creation — deletion blocked if assigned to any role or user."
}

Table role_permissions {
  id             uuid        [pk, default: `gen_random_uuid()`]
  role_id        uuid        [not null, ref: > roles.id]
  permission_id  uuid        [not null, ref: > permissions.id]
  granted_by_id  uuid        [ref: > users.id, note: "Admin who performed the assignment. NULL for Flyway-seeded associations."]
  granted_at     timestamptz [not null, default: `now()`]

  indexes {
    (role_id, permission_id) [unique, name: "uidx_role_permissions"]
    role_id [name: "idx_role_permissions_role"]
    permission_id [name: "idx_role_permissions_permission"]
  }

  Note: "Explicit join table for role-to-permission associations. Unique constraint enforces idempotent assignment (US-2.3). No soft-delete: revocation removes the row and is recorded in audit_logs."
}

Table user_roles {
  id             uuid        [pk, default: `gen_random_uuid()`]
  user_id        uuid        [not null, ref: > users.id]
  role_id        uuid        [not null, ref: > roles.id]
  granted_by_id  uuid        [ref: > users.id, note: "Admin who assigned. NULL for system-seeded assignments."]
  granted_at     timestamptz [not null, default: `now()`]
  expires_at     timestamptz [note: "NULL = permanent grant. Non-null enables future time-bounded role grants without schema changes."]

  indexes {
    (user_id, role_id) [unique, name: "uidx_user_roles"]
    user_id [name: "idx_user_roles_user"]
    role_id [name: "idx_user_roles_role"]
  }

  Note: "User-to-role assignments. Idempotent via unique constraint. Effective permission set = union of all role permissions + direct user_permissions at JWT issuance time."
}

Table user_permissions {
  id             uuid        [pk, default: `gen_random_uuid()`]
  user_id        uuid        [not null, ref: > users.id]
  permission_id  uuid        [not null, ref: > permissions.id]
  granted_by_id  uuid        [ref: > users.id, note: "Admin who granted the direct override."]
  granted_at     timestamptz [not null, default: `now()`]
  expires_at     timestamptz [note: "NULL = permanent. Supports time-bounded exceptional grants."]

  indexes {
    (user_id, permission_id) [unique, name: "uidx_user_permissions"]
    user_id [name: "idx_user_permissions_user"]
    permission_id [name: "idx_user_permissions_permission"]
  }

  Note: "Direct user-level permission overrides outside any role (US-2.7). Merged with role-derived permissions at JWT issuance. Revocation removes the row and is recorded in audit_logs."
}

// ============================================================
// SECTION 4 — TOKEN MANAGEMENT
// Only verification and reset tokens are persisted.
// No refresh tokens, no session tokens, no token revocation lists.
// Authentication is fully stateless via signed JWT access tokens.
// JWT invalidation is handled by users.credentials_updated_at.
// ============================================================

Table email_verification_tokens {
  id             uuid         [pk, default: `gen_random_uuid()`]
  user_id        uuid         [not null, ref: > users.id]
  token_hash     varchar(255) [not null, unique, note: "Hash of the UUID token delivered via email. Raw value never stored."]
  new_email      varchar(255) [note: "Populated only for email-change verification (US-1.11). NULL for initial registration verification."]
  expires_at     timestamptz  [not null, note: "Configurable TTL, e.g. 24 hours."]
  consumed_at    timestamptz  [note: "Set on successful verification. Enforces single-use semantics."]
  invalidated_at timestamptz  [note: "Set when superseded by a resend request (US-1.3) or administrative action."]
  created_at     timestamptz  [not null, default: `now()`]

  indexes {
    token_hash [unique, name: "uidx_email_verification_tokens_hash"]
    user_id [name: "idx_email_verification_tokens_user"]
    (user_id, consumed_at, invalidated_at) [name: "idx_email_verification_active", note: "Active token: WHERE consumed_at IS NULL AND invalidated_at IS NULL"]
    expires_at [name: "idx_email_verification_tokens_expires"]
  }

  Note: "Single-use, time-limited. Covers initial registration (new_email IS NULL) and email-change verification (new_email IS NOT NULL). At most one active token per user at a time — resend invalidates the previous token before creating a new one."
}

Table password_reset_tokens {
  id                uuid         [pk, default: `gen_random_uuid()`]
  user_id           uuid         [not null, ref: > users.id]
  token_hash        varchar(255) [not null, unique, note: "Hash of the cryptographically secure token delivered via email. Raw value never stored."]
  expires_at        timestamptz  [not null, note: "Short TTL: 30–60 minutes as configured. Non-negotiable for security."]
  consumed_at       timestamptz  [note: "Set on successful password reset. Enforces single-use."]
  invalidated_at    timestamptz  [note: "Set when a new reset request supersedes this token for the same user."]
  ip_address_masked varchar(45)  [note: "Requester IP at time of reset initiation, masked for LGPD."]
  created_at        timestamptz  [not null, default: `now()`]

  indexes {
    token_hash [unique, name: "uidx_password_reset_tokens_hash"]
    user_id [name: "idx_password_reset_tokens_user"]
    (user_id, consumed_at, invalidated_at) [name: "idx_password_reset_active", note: "One active reset per user at a time"]
    expires_at [name: "idx_password_reset_tokens_expires"]
  }

  Note: "Single-use, short-TTL. A new reset request invalidates all prior active tokens for the user before creating a new one. On successful reset: token is consumed and users.credentials_updated_at is updated, invalidating all previously issued JWTs."
}

// ============================================================
// SECTION 5 — OAUTH2
// ============================================================

Table oauth_accounts {
  id               uuid         [pk, default: `gen_random_uuid()`]
  user_id          uuid         [not null, ref: > users.id]
  provider_id      uuid         [not null, ref: > oauth_providers.id]
  provider_user_id varchar(255) [not null, note: "Stable unique identifier from the OAuth2 provider (e.g. Google sub claim). The primary identity anchor — must not change for a given provider account."]
  provider_email   varchar(255) [note: "Email reported by provider at last login. Not authoritative — users.email is the canonical email."]
  display_name     varchar(100) [note: "Display name from provider profile. Minimal PII; for UX convenience only. Not stored in JWT."]
  linked_at        timestamptz  [not null, default: `now()`]
  unlinked_at      timestamptz  [note: "Set when the user revokes the provider link (US-7.4). Row retained for audit history."]
  last_used_at     timestamptz  [note: "Updated on each successful OAuth2 authentication via this provider."]
  created_at       timestamptz  [not null, default: `now()`]
  updated_at       timestamptz  [not null, default: `now()`]

  indexes {
    (provider_id, provider_user_id) [unique, name: "uidx_oauth_accounts_provider_uid", note: "Primary OAuth2 resolution: find user by provider + provider_user_id"]
    user_id [name: "idx_oauth_accounts_user"]
    (user_id, provider_id, unlinked_at) [name: "idx_oauth_accounts_active_link", note: "Active link lookup: WHERE unlinked_at IS NULL"]
  }

  Note: "Google OAuth2 access/refresh tokens received during the OAuth2 flow are NOT stored — consumed transiently and discarded. provider_user_id (Google sub) is the stable identity anchor used for all subsequent logins. Soft-unlink (unlinked_at) preserves linking/unlinking audit history."
}

// ============================================================
// SECTION 6 — BRUTE FORCE PROTECTION & LOCKOUTS
// ============================================================

Table login_attempts {
  id                   uuid         [pk, default: `gen_random_uuid()`]
  user_id              uuid         [ref: > users.id, note: "NULL when no matching account was found for the submitted email — record is kept for IP-based attack pattern analysis without confirming email existence."]
  auth_method_id       uuid         [ref: > authentication_methods.id, note: "Method attempted: PASSWORD or GOOGLE_OAUTH2."]
  ip_address_masked    varchar(45)  [not null]
  user_agent_truncated varchar(512)
  was_successful       boolean      [not null]
  failure_context      varchar(50)  [note: "Internal classification only — NEVER returned to caller. e.g. INVALID_CREDENTIALS, ACCOUNT_LOCKED, UNVERIFIED_EMAIL, SOFT_DELETED. Anti-enumeration: all caller-facing errors are generic."]
  correlation_id       uuid         [not null]
  attempted_at         timestamptz  [not null, default: `now()`]

  indexes {
    user_id [name: "idx_login_attempts_user"]
    (user_id, was_successful, attempted_at) [name: "idx_login_attempts_user_outcome_time", note: "Failed attempt count for lockout threshold evaluation"]
    ip_address_masked [name: "idx_login_attempts_ip"]
    (ip_address_masked, attempted_at) [name: "idx_login_attempts_ip_time", note: "Rate limiting and credential-stuffing detection by source IP"]
    attempted_at [name: "idx_login_attempts_time"]
    correlation_id [name: "idx_login_attempts_correlation"]
  }

  Note: "Append-only log of all authentication attempts. failure_context is internal analysis data and is never returned to callers (anti-enumeration, US-6.3). user_id may be NULL for unknown-email attempts — this supports IP-level forensics without revealing whether the email exists in the system."
}

Table account_lockouts {
  id              uuid        [pk, default: `gen_random_uuid()`]
  user_id         uuid        [not null, ref: > users.id]
  lockout_type_id uuid        [not null, ref: > lockout_types.id]
  reason          text        [note: "Admin-provided reason for MANUAL lockouts; system message for AUTOMATIC."]
  locked_by_id    uuid        [ref: > users.id, note: "Admin who applied a MANUAL lock. NULL for AUTOMATIC threshold lockouts."]
  locked_at       timestamptz [not null, default: `now()`]
  expires_at      timestamptz [note: "NULL for MANUAL (permanent) locks. Set for AUTOMATIC lockouts with configured window (e.g. 15 min)."]
  unlocked_at     timestamptz [note: "Set on explicit admin unlock (US-8.3) or on auto-expiry resolution at next successful login attempt."]
  unlocked_by_id  uuid        [ref: > users.id, note: "Admin who unlocked. NULL for auto-expiry resolution."]
  created_at      timestamptz [not null, default: `now()`]

  indexes {
    user_id [name: "idx_account_lockouts_user"]
    (user_id, unlocked_at) [name: "idx_account_lockouts_active", note: "Current lockout: WHERE unlocked_at IS NULL"]
    locked_at [name: "idx_account_lockouts_time"]
    lockout_type_id [name: "idx_account_lockouts_type"]
  }

  Note: "Historical log of all lockout events. Current lockout state is also denormalized on users for fast login-path reads. MANUAL lockouts persist indefinitely until admin action; AUTOMATIC lockouts have expires_at and self-clear after the window. credentials_updated_at is updated when a lockout is applied to invalidate any previously issued JWTs."
}

// ============================================================
// SECTION 7 — AUDIT
// ============================================================

Table audit_logs {
  id                   uuid        [pk, default: `gen_random_uuid()`]
  event_type_id        uuid        [not null, ref: > audit_event_types.id]
  outcome_id           uuid        [not null, ref: > audit_outcomes.id]
  actor_user_id        uuid        [ref: > users.id, note: "User who performed the action. NULL for unauthenticated events (registration, public login, password reset initiation)."]
  target_user_id       uuid        [ref: > users.id, note: "Subject of the action. May equal actor_user_id for self-service flows (password change, logout)."]
  ip_address_masked    varchar(45) [note: "IPv4: last octet zeroed. IPv6: last 80 bits zeroed. LGPD-compliant."]
  user_agent_truncated varchar(512)
  correlation_id       uuid        [not null, note: "Per-request UUID injected into MDC. Returned in all error response bodies for incident correlation from client reports."]
  metadata             jsonb       [note: "Scrubbed, event-specific context JSON. Must be validated by AuditMetadataSanitizer before persistence — no passwords, raw tokens, or unmasked PII. Example: {role_id, permission_name, invalidation_reason}."]
  created_at           timestamptz [not null, default: `now()`]

  indexes {
    event_type_id [name: "idx_audit_logs_event_type"]
    outcome_id [name: "idx_audit_logs_outcome"]
    actor_user_id [name: "idx_audit_logs_actor"]
    target_user_id [name: "idx_audit_logs_target"]
    (target_user_id, created_at) [name: "idx_audit_logs_target_time", note: "Per-user activity timeline (US-5.5): reverse chronological"]
    (event_type_id, created_at) [name: "idx_audit_logs_type_time", note: "Admin filter: events of a type within a time range"]
    (actor_user_id, created_at) [name: "idx_audit_logs_actor_time"]
    correlation_id [name: "idx_audit_logs_correlation", note: "Distributed tracing: join all log entries from a single request"]
    created_at [name: "idx_audit_logs_time", note: "Retention pipeline: WHERE created_at < now() - retention_interval"]
  }

  Note: "Append-only structured audit log. No updated_at. No API delete or update endpoint. Purge only via scheduled retention pipeline (itself recorded as an audit entry). actor_user_id and target_user_id FKs are non-cascading: soft-deleted user rows retain their UUID, so referential integrity is preserved across the audit trail. HIGH and CRITICAL severity events (via audit_event_types.severity) are candidates for real-time SIEM/webhook alerting without schema changes. metadata JSONB is validated by a centralized sanitizer — no ad-hoc logging of sensitive values."
}

// ============================================================
// SECTION 8 — PRIVACY & LGPD CONSENT
// ============================================================

Table user_consents {
  id                   uuid         [pk, default: `gen_random_uuid()`]
  user_id              uuid         [not null, ref: > users.id]
  consent_version      varchar(20)  [not null, note: "Version of the legal consent document accepted (e.g. 1.0, 2.0). Enables future re-consent flows when terms are updated."]
  accepted_at          timestamptz  [not null, default: `now()`]
  ip_address_masked    varchar(45)  [note: "Masked IP at consent acceptance. Evidence of informed, active consent."]
  user_agent_truncated varchar(512)
  revoked_at           timestamptz  [note: "Set if user withdraws consent (future LGPD revocation flow). Triggers account deactivation pipeline."]
  revocation_reason    text         [note: "Reason for revocation, if provided."]
  created_at           timestamptz  [not null, default: `now()`]

  indexes {
    user_id [name: "idx_user_consents_user"]
    (user_id, consent_version) [name: "idx_user_consents_user_version"]
    (user_id, revoked_at) [name: "idx_user_consents_active", note: "Current consent: WHERE revoked_at IS NULL ORDER BY accepted_at DESC"]
    accepted_at [name: "idx_user_consents_time"]
  }

  Note: "LGPD consent tracking per user per document version (US-6.4). Separate from the denormalized users.consent_accepted_at — this table holds the full consent history. On terms update, users missing the new consent_version can be prompted to re-accept. Append-only history: prior consent rows are never deleted and form part of the LGPD compliance audit trail."
}

// ============================================================
// SECTION 9 — MFA READINESS (future-proof placeholder)
// ============================================================

Table mfa_configurations {
  id                 uuid         [pk, default: `gen_random_uuid()`]
  user_id            uuid         [not null, ref: > users.id]
  method_id          uuid         [not null, ref: > authentication_methods.id, note: "e.g. MFA_TOTP — values reserved in authentication_methods for future activation."]
  secret_hash        varchar(255) [note: "Hashed TOTP shared secret or equivalent. Never stored in plaintext."]
  is_enabled         boolean      [not null, default: false]
  verified_at        timestamptz  [note: "Set when user completes MFA enrollment challenge. NULL = enrolled but not yet verified."]
  last_used_at       timestamptz
  backup_codes_hash  varchar(255) [note: "Hash of the encrypted backup code bundle. Actual codes are delivered to the user once and not stored."]
  created_at         timestamptz  [not null, default: `now()`]
  updated_at         timestamptz  [not null, default: `now()`]

  indexes {
    (user_id, method_id) [unique, name: "uidx_mfa_configurations_user_method"]
    user_id [name: "idx_mfa_configurations_user"]
    is_enabled [name: "idx_mfa_configurations_enabled"]
  }

  Note: "Future-proof MFA scaffold. Not active in the current module release. Schema is present so MFA enrollment can be added as a feature without a structural migration. method_id references authentication_methods, which already reserves MFA_TOTP."
}
```

---

## Table Summary

| Table | Section | Purpose |
|---|---|---|
| `account_statuses` | Lookup | Account lifecycle states: ACTIVE, INACTIVE, LOCKED, PENDING_VERIFICATION |
| `auth_origins` | Lookup | Account creation method: LOCAL, GOOGLE, MIXED |
| `oauth_providers` | Lookup | Supported OAuth2 providers: GOOGLE (extensible) |
| `lockout_types` | Lookup | AUTOMATIC (threshold) vs MANUAL (admin-applied) lockouts |
| `permission_categories` | Lookup | Logical permission groupings for admin UI |
| `authentication_methods` | Lookup | How an auth attempt was made: PASSWORD, GOOGLE_OAUTH2, MFA_TOTP (future) |
| `audit_event_types` | Lookup | Full taxonomy of auditable events with severity and category |
| `audit_outcomes` | Lookup | SUCCESS, FAILURE |
| `users` | Identity | Core user identity, auth state, lockout state, credential version, LGPD anchors |
| `roles` | RBAC | Named, immutable role groupings |
| `permissions` | RBAC | Granular `resource:action` authority strings |
| `role_permissions` | RBAC | Many-to-many: role ↔ permission |
| `user_roles` | RBAC | Many-to-many: user ↔ role |
| `user_permissions` | RBAC | Direct user-level permission overrides outside roles |
| `email_verification_tokens` | Tokens | Single-use email verification (registration + email change) |
| `password_reset_tokens` | Tokens | Single-use short-TTL password reset tokens |
| `oauth_accounts` | OAuth2 | Provider-to-user linkages; soft-unlink with history |
| `login_attempts` | Security | Append-only auth attempt log for brute force analysis |
| `account_lockouts` | Security | Lockout event history; distinguishes automatic vs manual |
| `audit_logs` | Audit | Append-only structured security event log |
| `user_consents` | Privacy | LGPD consent version history per user |
| `mfa_configurations` | MFA | Future-proof TOTP enrollment scaffold (inactive) |

---

## Key Design Decisions

### No Raw Token Storage
`email_verification_tokens.token_hash` and `password_reset_tokens.token_hash` store only cryptographic hashes. Raw token values are transmitted once (via email) and discarded immediately — a compromised database yields no usable tokens.

### Lightweight Stateless JWT Invalidation
`users.credentials_updated_at` is the single mechanism for invalidating previously issued JWTs without a session table or token revocation list. On every authenticated request, the JWT `iat` claim is compared against this timestamp. Any JWT issued before this value is rejected. The field is updated on: password change, password reset, account disable, and admin-triggered forced re-authentication. This requires one fast indexed PK lookup per request and remains architecturally stateless.

### No ENUM Types
Every categorical column uses a lookup table FK. New states, providers, event types, and permission categories are added via Flyway data migrations — zero schema changes required.

### Soft-Delete + LGPD Anonymization
`users.deleted_at` soft-deletes the record. A separate `anonymized_at` marks when the LGPD erasure pipeline has zeroed PII fields (`email`, `display_name`, `password_hash`). The UUID row is never deleted, preserving all `audit_logs` foreign key integrity.

### Append-Only Audit Log
`audit_logs` has no `updated_at`, no API update endpoint, and no API delete endpoint. Purge is exclusively via a scheduled retention pipeline, which itself creates an audit entry. `metadata` JSONB is sanitized by a centralized utility before persistence — no sensitive values leak into the audit trail.

### Lockout Denormalization
Current lockout state is denormalized onto `users` (`lockout_expires_at`, `lockout_type_id`) for fast login-path reads without a JOIN. Full lockout history lives in `account_lockouts`.