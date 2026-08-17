package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.response.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Version", description = "Deployed backend build info — used to verify a deploy actually rolled out")
@RestController
@RequestMapping("/api/v1/version")
public class VersionController {

    private final BuildProperties buildProperties;

    public VersionController(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Operation(summary = "Backend build info", description = "Returns the version, git commit, and build time baked into the running image.")
    @GetMapping
    public VersionResponse getVersion() {
        return new VersionResponse(
                buildProperties.getVersion(),
                buildProperties.get("commit"),
                buildProperties.getTime().toString());
    }
}
