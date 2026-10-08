package com.ncba.countryInfoService.controller;

import com.ncba.countryInfoService.dto.CountryRequest;
import com.ncba.countryInfoService.dto.CountryResponse;
import com.ncba.countryInfoService.dto.CountryUpdateRequest;
import com.ncba.countryInfoService.service.CountryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

import static org.hibernate.dialect.SybaseASEDialect.MAX_PAGE_SIZE;

@RestController
@RequestMapping("/api/v1/countries")
public class CountryController {
    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CountryResponse> create(@Valid @RequestBody CountryRequest request) {
        CountryService.UpsertResult result = service.fetchAndStore(request.name());
        if (result.created()) {
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}").buildAndExpand(result.country().id()).toUri();
            return ResponseEntity.created(location).body(result.country());
        }
        // Already stored: data was refreshed from the SOAP service, nothing new was created
        return ResponseEntity.ok(result.country());
    }

    @GetMapping
    public PagedModel<CountryResponse> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new PagedModel<>(service.findAll(PageRequest.of(safePage, safeSize, Sort.by("name"))));
    }

    @GetMapping("/{id}")
    public CountryResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PutMapping("/{id}")
    public CountryResponse update(@PathVariable Long id, @Valid @RequestBody CountryUpdateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
