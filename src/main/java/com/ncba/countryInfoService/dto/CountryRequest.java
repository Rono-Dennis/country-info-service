package com.ncba.countryInfoService.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CountryRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        @Pattern(regexp = "^[\\p{L} .'()&,-]+$", message = "name contains invalid characters")
        String name) {
}
