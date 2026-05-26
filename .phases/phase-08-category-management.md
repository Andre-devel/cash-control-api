## Phase 8 — Category Management

**Objective:** Implement full category lifecycle: list, create, edit, hide, archive, auto-suggest, and category rules.

**Dependencies:** Phase 3 complete.

**Complexity:** Medium

### Phase 8.1 — Category Service

**Implementation Tasks:**

- [ ] Create `CategoryRepository.java`:
  - `findAllSystemCategories()` — `user_id IS NULL`
  - `findAllByUserId(UUID userId)` — user-defined categories
  - `existsByUserIdAndParentIdAndName(UUID userId, UUID parentId, String name)`
- [ ] Create `CategoryService.java` and `CategoryServiceImpl.java`:
  - `listCategories(UUID userId, boolean includeHidden, boolean includeArchived)` — returns system + user categories; nested subcategories
  - `createCategory(CreateCategoryRequest, UUID userId)` — validates max depth (root or subcategory only); validates name uniqueness within parent scope
  - `editCategory(UUID id, EditCategoryRequest, UUID userId)` — only user-defined categories; validates name uniqueness
  - `setHidden(UUID id, boolean hidden, UUID userId)` — works on system and user categories
  - `archiveCategory(UUID id, UUID userId)` — archives parent and all subcategories; user-defined only
  - `unarchiveCategory(UUID id, UUID userId)`
  - `suggestCategory(String description, UUID userId)` — frequency-based suggestion from transaction history; falls back to most-used categories
  - `createRule(CreateCategoryRuleRequest, UUID userId)`
  - `listRules(UUID userId)`
  - `deleteRule(UUID id, UUID userId)`

**Acceptance Criteria:**
- [ ] System categories cannot be archived; `BusinessRuleException` on attempt
- [ ] Subcategory nesting limited to two levels; third-level creation → 422
- [ ] `archiveCategory` propagates to all subcategories atomically
- [ ] `suggestCategory` returns at least one suggestion when the user has transaction history

**Automated Tests:**
- [ ] `CategoryServiceTest` — unit tests for all methods
- [ ] `CategorySuggestionTest` — asserts suggestion accuracy from seeded history

---

### Phase 8.2 — Category Controller

**Implementation Tasks:**

- [ ] Create `CategoryController.java` — `@RestController @RequestMapping("/api/v1/categories")`:
  - `GET /` → list (with `includeHidden`, `includeArchived` params) → 200
  - `POST /` → create → 201
  - `PUT /{id}` → edit → 200
  - `POST /{id}/hide` and `POST /{id}/show` → 200
  - `POST /{id}/archive` and `POST /{id}/unarchive` → 200
  - `GET /suggest?description=...` → suggestions → 200
  - `POST /rules` → create rule → 201
  - `GET /rules` → list rules → 200
  - `DELETE /rules/{id}` → 204

**Automated Tests:**
- [ ] `CategoryControllerTest` — HTTP validation for all endpoints

---

