package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Category or subcategory with optional nested children")
public record CategoryResponse(
        @Schema(description = "Category UUID") UUID id,
        @Schema(description = "Owning user UUID. Null for system default categories that are shared across all users.") UUID userId,
        @Schema(description = "Parent category UUID. Null for root-level categories.") UUID parentId,
        @Schema(description = "Parent category name. Null for root-level categories.") String parentName,
        @Schema(description = "Category name", example = "Food & Dining") String name,
        @Schema(description = "Hex color code for UI rendering", example = "#FF5733") String color,
        @Schema(description = "Icon identifier from the predefined set", example = "restaurant") String icon,
        @Schema(description = "User-defined display order. Lower values appear first.", example = "0") int sortOrder,
        @Schema(description = "True for system-provided categories. System categories cannot be deleted; they may only be hidden.") boolean isDefault,
        @Schema(description = "True when this category is hidden from transaction pickers. Existing categorizations are unaffected.") boolean isHidden,
        @Schema(description = "True when this category is archived and cannot receive new transactions.") boolean isArchived,
        @Schema(description = "Timestamp when the category was archived. Null if active.") Instant archivedAt,
        @Schema(description = "Nested subcategories. Empty for subcategories (max depth is 2).") List<CategoryResponse> subcategories,
        @Schema(description = "Record creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt
) {}
