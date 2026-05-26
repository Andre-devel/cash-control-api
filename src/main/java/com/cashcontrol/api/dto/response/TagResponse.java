package com.cashcontrol.api.dto.response;

import java.util.UUID;

public record TagResponse(UUID id, String name, String color) {}
