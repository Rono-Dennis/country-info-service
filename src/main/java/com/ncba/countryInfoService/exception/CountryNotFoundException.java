package com.ncba.countryInfoService.exception;

public class CountryNotFoundException extends RuntimeException {
    public CountryNotFoundException(String message) {
        super(message);
    }

    public static CountryNotFoundException byName(String name) {
        return new CountryNotFoundException("Country '" + name + "' was not found");
    }

    public static CountryNotFoundException byIsoCode(String isoCode) {
        return new CountryNotFoundException("No country found for ISO code '" + isoCode + "'");
    }

    public static CountryNotFoundException byId(Long id) {
        return new CountryNotFoundException("Country with id " + id + " does not exist");
    }
}
