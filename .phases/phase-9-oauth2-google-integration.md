## Phase 9 — OAuth2 Google Integration

**Objective:** Implement Google OAuth2 login, auto-registration, and account linking. The resulting JWT is structurally identical to one issued via local login.

**Dependencies:** Phase 6 and 8 complete.

**Complexity:** High

### Phase 9.1 — OAuth2 Success Handler

**Implementation Tasks:**

- [ ] Create `OAuth2AuthenticationSuccessHandler.java implements AuthenticationSuccessHandler`:
  - Extract `OidcUser` or `OAuth2User` from authentication
  - Extract: `email`, `displayName`, `providerUserId` (Google `sub` claim)
  - Resolve `OauthProvider` entity for Google by slug
  - Look up `OauthAccount` by `(providerId, providerUserIdValue)`:
    - If found and not unlinked: this is a returning user → proceed to JWT issuance
    - If not found: check if email exists as LOCAL account
      - If email exists (LOCAL account): link accounts → update `auth_origin` to `MIXED`, create `OauthAccount`, record `ACCOUNT_LINKED_GOOGLE`
      - If email does not exist: create new `User` with status `ACTIVE`, `emailVerifiedAt = now()`, `origin = GOOGLE`, create `OauthAccount`, record `USER_REGISTERED_GOOGLE`
  - Resolve effective permissions via `PermissionResolver`
  - Issue JWT via `JwtService`
  - Redirect to `${OAUTH2_SUCCESS_REDIRECT_URL}?token=<jwt>` (frontend receives token in query param)
  - Record `AUTH_SUCCESS` audit event
  - Google OAuth2 access/refresh tokens are **not stored** — discarded after profile extraction
- [ ] Create `OAuth2UserInfoExtractor.java` — extracts and validates required fields from `OAuth2User`; throws `OAuthProviderException` if email or sub is missing

**Acceptance Criteria:**
- [ ] New Google user: `ACTIVE` account created, email marked verified
- [ ] Existing local user: accounts linked to `MIXED`, local password preserved
- [ ] Returning Google user: JWT issued, `last_used_at` updated on `oauth_accounts`
- [ ] Google tokens (access/refresh) are not persisted anywhere
- [ ] Brute-force lockout check applied after user resolution

**Automated Tests:**
- [ ] `OAuth2SuccessHandlerTest` (unit, mocked repos):
  - New user flow creates `User` + `OauthAccount`
  - Existing local user flow creates `OauthAccount` and sets origin to `MIXED`
  - Returning Google user updates `lastUsedAt`
  - Missing email in Google profile throws `OAuthProviderException`

---

### Phase 9.2 — OAuth2 Failure Handler & Unlink Flow

**Implementation Tasks:**

- [ ] Create `OAuth2AuthenticationFailureHandler.java implements AuthenticationFailureHandler`:
  - Log failure reason internally (not forwarded to client)
  - Redirect to `${OAUTH2_FAILURE_REDIRECT_URL}?error=oauth_failed`
  - Record `AUTH_FAILURE` audit event with provider context in metadata
  - No partial user records created
- [ ] Create `OAuthProviderService.java`:
  - `void unlinkProvider(UUID userId, String providerSlug)`:
    1. Find active `OauthAccount` for user + provider
    2. If user is `GOOGLE`-only with no local password: reject with 409 (would lock out user)
    3. Set `unlinkedAt = now()` on `OauthAccount`
    4. If was `MIXED`: set `authOrigin` back to `LOCAL`
    5. Optionally update `credentialsUpdatedAt` per config
    6. Record `PROVIDER_UNLINKED` audit event
- [ ] Add `DELETE /api/v1/auth/provider/{providerSlug}` → `@PreAuthorize("isAuthenticated()")`

**Acceptance Criteria:**
- [ ] OAuth2 failure produces no orphaned user records
- [ ] Google-only account cannot unlink without a local password set
- [ ] State parameter CSRF validation is handled by Spring Security OAuth2 Client by default (must not disable it)

**Automated Tests:**
- [ ] `OAuth2FailureHandlerTest` — no DB writes on failure; audit event recorded
- [ ] `OAuthProviderServiceTest` — GOOGLE-only user unlink throws `ConflictException`; MIXED user unlink succeeds

---

