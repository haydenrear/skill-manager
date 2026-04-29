package dev.skillmanager.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishResponse(
        String name,
        String version,
        String sha256,
        long sizeBytes,
        String downloadUrl
) {}
