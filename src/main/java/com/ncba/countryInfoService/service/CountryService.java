package com.ncba.countryInfoService.service;

import com.ncba.countryInfoService.dto.CountryResponse;
import com.ncba.countryInfoService.dto.CountryUpdateRequest;
import com.ncba.countryInfoService.exception.CountryNotFoundException;
import com.ncba.countryInfoService.model.CountryInfo;
import com.ncba.countryInfoService.repository.CountryInfoRepository;
import com.ncba.countryInfoService.client.CountrySoapClient;
import com.ncba.countryInfoService.client.wsdl.TCountryInfo;
import com.ncba.countryInfoService.util.TextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class CountryService {

    private final CountrySoapClient soapClient;
    private final TransactionTemplate tx;
    private final CountryInfoRepository repository;
    private final CountryMapper mapper;

    public CountryService(CountrySoapClient soapClient, TransactionTemplate tx, CountryInfoRepository repository, CountryMapper mapper) {
        this.soapClient = soapClient;
        this.tx = tx;
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<CountryResponse> findAll(Pageable pageable) {
        return repository.findAll(pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public CountryResponse findById(Long id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> CountryNotFoundException.byId(id));
    }

    @Transactional
    public CountryResponse update(Long id, CountryUpdateRequest request) {
        CountryInfo entity = repository.findById(id).orElseThrow(() -> CountryNotFoundException.byId(id));
        mapper.applyUpdate(request, entity);
        CountryResponse response = mapper.toResponse(repository.saveAndFlush(entity));
        log.info("country_updated id={} isoCode={}", id, entity.getIsoCode());
        return response;
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw CountryNotFoundException.byId(id);
        }
        repository.deleteById(id);
        log.info("country_deleted id={}", id);
    }

    public record UpsertResult(CountryResponse country, boolean created) {
    }

    public UpsertResult fetchAndStore(String rawName) {
        String name = TextUtils.toSentenceCase(rawName);
        log.info("lookup_started requestedName={} normalizedName={}", rawName, name);

        String isoCode = soapClient.getIsoCode(name);
        TCountryInfo info = soapClient.getFullInfo(isoCode);

        UpsertResult result = tx.execute(status -> {
            CountryInfo entity = repository.findByIsoCode(isoCode).orElse(null);
            boolean created = entity == null;
            if (created) {
                entity = new CountryInfo();
            }
            mapper.applySoap(info, entity);
            return new UpsertResult(mapper.toResponse(repository.saveAndFlush(entity)), created);
        });

        log.info("lookup_stored isoCode={} id={} created={}", isoCode, result.country().id(), result.created());
        return result;
    }
}
