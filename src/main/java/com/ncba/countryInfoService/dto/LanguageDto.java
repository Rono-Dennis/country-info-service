package com.ncba.countryInfoService.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LanguageDto(
        @NotBlank(message = "language isoCode is required")
        @Size(max = 10)
        String isoCode,
        @NotBlank(message = "language name is required")
        @Size(max = 255)
        String name) {
}
