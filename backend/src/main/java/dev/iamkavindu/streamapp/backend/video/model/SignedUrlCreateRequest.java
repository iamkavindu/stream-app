package dev.iamkavindu.streamapp.backend.video.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignedUrlCreateRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must not exceed 255 characters")
        String fileName,

        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "must be exactly 64 hexadecimal characters")
        String sha256Hex
) {}
