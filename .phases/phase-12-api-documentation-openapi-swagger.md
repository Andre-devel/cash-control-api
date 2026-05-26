## Phase 12 — API Documentation (OpenAPI / Swagger)

**Objective:** Expose self-documenting OpenAPI specification via Springdoc.

**Dependencies:** Phases 4–10 complete.

**Complexity:** Low

### Phase 12.1 — OpenAPI Configuration

**Implementation Tasks:**

- [ ] Add `springdoc-openapi-starter-webmvc-ui` to `build.gradle.kts`
- [ ] Create `OpenApiConfig.java` — `@Configuration`:
  - Set API title: `Cash Control API`
  - Set version: `v1`
  - Set description aligned with project-description.md
  - Add JWT `BearerAuth` security scheme
  - Apply global security requirement so all endpoints show the lock icon in Swagger UI
- [ ] Annotate all controllers with `@Tag(name = "...", description = "...")`
- [ ] Annotate key endpoints with `@Operation(summary = "...")` and `@ApiResponse` codes
- [ ] Annotate all DTOs with `@Schema` where field-level description adds value

**Acceptance Criteria:**
- [ ] Swagger UI accessible at `/swagger-ui/index.html`
- [ ] OpenAPI JSON available at `/v3/api-docs`
- [ ] All endpoints visible with correct HTTP methods and response codes

**Automated Tests:**
- [ ] `OpenApiSmokeTest` — asserts `/v3/api-docs` returns 200 and contains expected path count

---

