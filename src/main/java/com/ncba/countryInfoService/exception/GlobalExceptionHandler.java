package com.ncba.countryInfoService.exception;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.oxm.XmlMappingException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(CountryNotFoundException.class)
    ProblemDetail handleNotFound(CountryNotFoundException ex) {
        log.info("not_found message={}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Country not found", ex.getMessage());
    }

    @ExceptionHandler(ExternalServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable(ExternalServiceUnavailableException ex) {
        log.warn("external_service_unavailable message={} cause={}", ex.getMessage(), String.valueOf(ex.getCause()));
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "Upstream service unavailable", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "20").body(pd);
    }

    /** SOAP reply could not be mapped to our JAXB classes: upstream sent something unexpected. */
    @ExceptionHandler(XmlMappingException.class)
    ProblemDetail handleBadUpstreamPayload(XmlMappingException ex) {
        log.error("upstream_payload_error", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Invalid upstream response",
                "The country information provider returned an unexpected response.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException ex) {
        log.warn("data_conflict message={}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Data conflict",
                "The request conflicts with existing data (for example a duplicate ISO code). Please retry.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Parameter '" + ex.getName() + "' has an invalid value.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("unhandled_exception", ex);
        // Never leak internals to the client
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred. Quote the correlationId when contacting support.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            enrich(pd);
        }
        return response;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        enrich(pd);
        return pd;
    }

    private void enrich(ProblemDetail pd) {
        pd.setProperty("timestamp", Instant.now().toString());
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
    }
}
