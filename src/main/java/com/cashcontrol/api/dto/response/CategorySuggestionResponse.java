package com.cashcontrol.api.dto.response;

import java.util.UUID;

public record CategorySuggestionResponse(
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        long matchCount
) {}
