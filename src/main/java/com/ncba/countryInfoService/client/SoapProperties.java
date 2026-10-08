package com.ncba.countryInfoService.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "soap")
public record SoapProperties(String endpoint, Duration connectTimeout, Duration readTimeout) {
}
