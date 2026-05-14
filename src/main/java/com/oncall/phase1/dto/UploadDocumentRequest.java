package com.oncall.phase1.dto;

import jakarta.validation.constraints.NotBlank;

public record UploadDocumentRequest(
        @NotBlank(message = "id is required")
        String id,
        @NotBlank(message = "html is required")
        String html
) {
}
