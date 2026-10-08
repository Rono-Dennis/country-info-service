package com.ncba.countryInfoService.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CountryUpdateRequest(
        @Size(min = 1, max = 100, message = "name must be 1-100 characters") String name,
        @Size(max = 255) String capitalCity,
        @Size(max = 20) String phoneCode,
        @Size(max = 10) String continentCode,
        @Size(max = 10) String currencyIsoCode,
        @Size(max = 500) String countryFlag,
        @Valid List<LanguageDto> languages) {
}

