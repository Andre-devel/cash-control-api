package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Deployed backend build info")
public record VersionResponse(
        @Schema(description = "Project version from build.gradle.kts", example = "0.0.1-SNAPSHOT") String version,
        @Schema(description = "Short git commit SHA baked in at image build time", example = "a1b2c3d") String commit,
        @Schema(description = "UTC instant the jar was built", example = "2026-08-17T14:32:05.123Z") String buildTime
) {}
