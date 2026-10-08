package com.ncba.countryInfoService.dto;

import java.time.Instant;
import java.util.List;

public record CountryResponse(
        Long id,
        String isoCode,
        String name,
        String capitalCity,
        String phoneCode,
        String continentCode,
        String currencyIsoCode,
        String countryFlag,
        List<LanguageDto> languages,
Instant createdAt,
Instant updatedAt) {
        }
