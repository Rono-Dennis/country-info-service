package com.ncba.countryInfoService.client;

import com.ncba.countryInfoService.exception.CountryNotFoundException;
import com.ncba.countryInfoService.client.wsdl.*;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.ws.client.core.WebServiceTemplate;

import java.util.Locale;
import java.util.function.Supplier;

@Slf4j
@Component
public class CountrySoapClient {
    private static final String NOT_FOUND_MARKER = "not found";
    static final String INSTANCE = "countrySoap";
    private final MeterRegistry meterRegistry;
    private final WebServiceTemplate template;

    public CountrySoapClient(MeterRegistry meterRegistry, WebServiceTemplate template) {
        this.meterRegistry = meterRegistry;
        this.template = template;
    }

    @Cacheable(cacheNames = "isoCodes", key = "#countryName")
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE, fallbackMethod = "isoCodeFallback")
    public String getIsoCode(String countryName) {
        CountryISOCode request = new CountryISOCode();
        request.setSCountryName(countryName);

        log.info("request={} ",request);
        CountryISOCodeResponse response = timed("CountryISOCode", () ->
                (CountryISOCodeResponse) template.marshalSendAndReceive(request));

        log.info("response={} ",response);
        String iso = response == null ? null : response.getCountryISOCodeResult();
        log.info("iso={} ",iso);
        if (iso == null || iso.isBlank() || iso.toLowerCase(Locale.ROOT).contains(NOT_FOUND_MARKER)) {
            throw CountryNotFoundException.byName(countryName);
        }
        return iso.trim();
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        long start = System.nanoTime();
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("soap.client.requests")
                    .description("Latency of outbound SOAP calls")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            log.info("soap_call operation={} outcome={} durationMs={}",
                    operation, outcome, (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Cacheable(cacheNames = "countryInfo", key = "#isoCode")
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE, fallbackMethod = "fullInfoFallback")
    public TCountryInfo getFullInfo(String isoCode) {
        FullCountryInfo request = new FullCountryInfo();
        request.setSCountryISOCode(isoCode);

        FullCountryInfoResponse response = timed("FullCountryInfo", () ->
                (FullCountryInfoResponse) template.marshalSendAndReceive(request));

        log.info("response={} ",response);
        TCountryInfo info = response == null ? null : response.getFullCountryInfoResult();
        if (info == null || info.getSName() == null || info.getSName().isBlank()
                || info.getSName().toLowerCase(Locale.ROOT).contains(NOT_FOUND_MARKER)) {
            throw CountryNotFoundException.byIsoCode(isoCode);
        }
        return info;
    }
}
