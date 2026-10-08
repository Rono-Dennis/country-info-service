package com.ncba.countryInfoService.repository;

import com.ncba.countryInfoService.model.CountryInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CountryInfoRepository extends JpaRepository<CountryInfo, Long> {

    Optional<CountryInfo> findByIsoCode(String isoCode);
}
