## Phase 13 — OpenAPI Documentation

**Objective:** Document all API endpoints with OpenAPI 3.x via SpringDoc. Provide frontend-ready auth flow contracts.

**Dependencies:** Phase 8 complete.

**Complexity:** Low

### Phase 13.1 — OpenAPI Configuration

**Implementation Tasks:**

- [ ] Create `OpenApiConfig.java @Configuration`:
  - `OpenAPI` bean with: title, description, version, contact, license
  - `SecurityScheme`: `bearerAuth`, type `HTTP`, scheme `bearer`, bearerFormat `JWT`
  - `SecurityRequirement` applied globally
- [ ] Annotate all controllers with `@Tag(name = "...")` for grouping
- [ ] Annotate all endpoints with `@Operation(summary = "...", description = "...")`
- [ ] Document all response codes with `@ApiResponse`: 200, 201, 400, 401, 403, 404, 409, 429, 500
- [ ] Document `ErrorResponse` schema with `@Schema`
- [ ] Document auth flow in `description` field:
  - JWT Bearer: `Authorization: Bearer <token>` header
  - OAuth2 Google: redirect flow with token returned in redirect URL
  - Token expiry: re-authenticate via `POST /api/v1/auth/login`

**Acceptance Criteria:**
- [ ] `GET /v3/api-docs` returns valid OpenAPI 3.1 JSON
- [ ] `GET /swagger-ui/index.html` renders all endpoints
- [ ] JWT Bearer auth scheme configurable in Swagger UI (test authenticated requests)
- [ ] No internal class names or package paths in API documentation

**Automated Tests:**
- [ ] `OpenApiTest` — `GET /v3/api-docs` returns 200; response parses as valid OpenAPI JSON; all expected paths present

---

