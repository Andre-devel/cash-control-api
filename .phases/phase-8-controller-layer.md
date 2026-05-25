## Phase 8 — Controller Layer

**Objective:** Implement all `@RestController` classes. Controllers validate input via `@Valid`, delegate to services, and return standardized DTOs. No business logic in controllers.

**Dependencies:** Phase 7 complete.

**Complexity:** Medium

### Phase 8.1 — Authentication Controller

**Implementation Tasks:**

- [ ] Create `AuthController.java @RestController @RequestMapping("/api/v1/auth")`:
  - `POST /register` → `authService.register()` → 201
  - `POST /login` → `authService.login()` → 200 with `Cache-Control: no-store`
  - `POST /logout` → `authService.logout()` → 204 (requires valid JWT)
  - `GET /me` → `userService.getOwnProfile()` → 200 (requires JWT)
  - `POST /password/change` → `authService.changePassword()` → 204 (requires JWT)
  - `POST /password-reset/request` → `passwordResetService.initiateReset()` → 200
  - `POST /password-reset/confirm` → `passwordResetService.completeReset()` → 200
  - `GET /email/verify` → `emailVerificationService.verifyEmail()` → 200
  - `POST /email/verify/resend` → `emailVerificationService.resendVerification()` → 200
  - `POST /email/change` → `emailVerificationService.initiateEmailChange()` → 200 (requires JWT)
  - `@Valid` on all request body parameters
  - `@PreAuthorize("isAuthenticated()")` on endpoints requiring JWT

**Acceptance Criteria:**
- [ ] `POST /login` response includes `Cache-Control: no-store` header
- [ ] `POST /register` returns 201 with uniform success message regardless of email conflict
- [ ] `POST /password-reset/request` always returns 200 regardless of email existence
- [ ] No controller method contains business logic (only delegation + response mapping)

**Automated Tests:**
- [ ] `AuthControllerTest` (`@WebMvcTest`):
  - Valid register → 201
  - Login with mocked service → 200 with JWT in body
  - Login missing body → 400 with fieldErrors
  - Logout without token → 401
  - `POST /password-reset/request` with non-existent email → 200 (anti-enumeration)

---

### Phase 8.2 — User Management Controller

**Implementation Tasks:**

- [ ] Create `UserController.java @RestController @RequestMapping("/api/v1/users")`:
  - `GET /me` → own profile (requires JWT)
  - `PUT /me` → update own profile (requires JWT)
  - `GET /{userId}` → `@PreAuthorize("hasAuthority('user:read')")` → admin view
  - `GET /` → `@PreAuthorize("hasAuthority('user:read')")` → paginated list
  - `POST /` → `@PreAuthorize("hasAuthority('user:create')")` → admin create
  - `PUT /{userId}/disable` → `@PreAuthorize("hasAuthority('user:update')")`
  - `PUT /{userId}/activate` → `@PreAuthorize("hasAuthority('user:update')")`
  - `DELETE /{userId}` → `@PreAuthorize("hasAuthority('user:delete')")` → soft delete

**Automated Tests:**
- [ ] `UserControllerTest` (`@WebMvcTest`):
  - Unauthenticated `GET /me` → 401
  - `GET /{userId}` without `user:read` authority → 403
  - `GET /{userId}` response never contains `passwordHash`
  - Pagination parameters validated (page ≥ 0, size 1–100)

---

### Phase 8.3 — RBAC Controllers

**Implementation Tasks:**

- [ ] Create `RoleController.java @RequestMapping("/api/v1/roles")`:
  - `POST /` → `@PreAuthorize("hasAuthority('role:create')")`
  - `GET /` → `@PreAuthorize("hasAnyAuthority('role:create','role:update')")`
  - `GET /{roleId}` → `@PreAuthorize("hasAnyAuthority('role:create','role:update')")`
  - `PUT /{roleId}` → `@PreAuthorize("hasAuthority('role:update')")`
  - `DELETE /{roleId}` → `@PreAuthorize("hasAuthority('role:delete')")`
  - `POST /{roleId}/permissions` → `@PreAuthorize("hasAuthority('permission:grant')")`
  - `DELETE /{roleId}/permissions/{permissionId}` → `@PreAuthorize("hasAuthority('permission:revoke')")`
- [ ] Create `PermissionController.java @RequestMapping("/api/v1/permissions")`:
  - `POST /` → `@PreAuthorize("hasAuthority('permission:grant')")`
  - `GET /` → `@PreAuthorize("hasAnyAuthority('permission:grant','audit:view')")`
  - `DELETE /{permissionId}` → `@PreAuthorize("hasAuthority('permission:revoke')")`
- [ ] Create `UserRoleController.java @RequestMapping("/api/v1/users/{userId}/roles")`:
  - `POST /` → `@PreAuthorize("hasAuthority('role:update')")`
  - `DELETE /{roleId}` → `@PreAuthorize("hasAuthority('role:update')")`
- [ ] Create `UserPermissionController.java @RequestMapping("/api/v1/users/{userId}/permissions")`:
  - `POST /` → `@PreAuthorize("hasAuthority('permission:grant')")`
  - `DELETE /{permissionId}` → `@PreAuthorize("hasAuthority('permission:revoke')")`

**Automated Tests:**
- [ ] `RoleControllerTest`: missing authority → 403; duplicate name → 409; system role delete → 409
- [ ] Authorization matrix test: matrix of all endpoints × all permission combinations

---

### Phase 8.4 — Audit & Admin Security Controllers

**Implementation Tasks:**

- [ ] Create `AuditController.java @RequestMapping("/api/v1/audit")`:
  - `GET /` → `@PreAuthorize("hasAuthority('audit:view')")` → paginated, filtered
  - `GET /users/{userId}` → `@PreAuthorize("hasAuthority('audit:view')")` → per-user timeline
  - `GET /summary` → `@PreAuthorize("hasAuthority('audit:view')")` → security summary
- [ ] Create `AdminSecurityController.java @RequestMapping("/api/v1/admin/security")`:
  - `POST /force-reauth` → `@PreAuthorize("hasAuthority('auth:manage')")`
  - `POST /lock` → `@PreAuthorize("hasAuthority('auth:manage')")`
  - `POST /unlock` → `@PreAuthorize("hasAuthority('auth:manage')")`

**Acceptance Criteria:**
- [ ] Audit log API is read-only (no POST/PUT/DELETE on audit entries)
- [ ] All responses paginated where list endpoints are involved
- [ ] No raw user data in audit responses — masked/truncated only

**Automated Tests:**
- [ ] `AuditControllerTest`: without `audit:view` → 403; pagination parameters honored; no PII in response

---

