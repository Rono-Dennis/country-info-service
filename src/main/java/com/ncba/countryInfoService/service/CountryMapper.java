package com.ncba.countryInfoService.service;

import com.ncba.countryInfoService.dto.CountryResponse;
import com.ncba.countryInfoService.dto.CountryUpdateRequest;
import com.ncba.countryInfoService.dto.LanguageDto;
import com.ncba.countryInfoService.model.CountryInfo;
import com.ncba.countryInfoService.model.Language;
import com.ncba.countryInfoService.client.wsdl.TCountryInfo;
import com.ncba.countryInfoService.client.wsdl.TLanguage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CountryMapper {

    public CountryResponse toResponse(CountryInfo c) {
        List<LanguageDto> languages = c.getLanguages().stream()
                .map(l -> new LanguageDto(l.getIsoCode(), l.getName()))
                .toList();
        return new CountryResponse(c.getId(), c.getIsoCode(), c.getName(), c.getCapitalCity(), c.getPhoneCode(),
                c.getContinentCode(), c.getCurrencyIsoCode(), c.getCountryFlag(), languages,
                c.getCreatedAt(), c.getUpdatedAt());
    }

    /** Copies the SOAP payload onto the entity (new or existing) and replaces its languages. */
    public void applySoap(TCountryInfo src, CountryInfo target) {
        target.setIsoCode(src.getSISOCode());
        target.setName(src.getSName());
        target.setCapitalCity(src.getSCapitalCity());
        target.setPhoneCode(src.getSPhoneCode());
        target.setContinentCode(src.getSContinentCode());
        target.setCurrencyIsoCode(src.getSCurrencyISOCode());
        target.setCountryFlag(src.getSCountryFlag());

        List<Language> languages = src.getLanguages().stream().map(this::toLanguage).toList();
        target.replaceLanguages(languages);
    }

    public void applyUpdate(CountryUpdateRequest req, CountryInfo target) {
        if (req.name() != null) target.setName(req.name());
        if (req.capitalCity() != null) target.setCapitalCity(req.capitalCity());
        if (req.phoneCode() != null) target.setPhoneCode(req.phoneCode());
        if (req.continentCode() != null) target.setContinentCode(req.continentCode());
        if (req.currencyIsoCode() != null) target.setCurrencyIsoCode(req.currencyIsoCode());
        if (req.countryFlag() != null) target.setCountryFlag(req.countryFlag());
        if (req.languages() != null) {
            target.replaceLanguages(req.languages().stream().map(d -> {
                Language l = new Language();
                l.setIsoCode(d.isoCode());
                l.setName(d.name());
                return l;
            }).toList());
        }
    }

    private Language toLanguage(TLanguage src) {
        Language l = new Language();
        l.setIsoCode(src.getSISOCode());
        l.setName(src.getSName());
        return l;
    }
}
