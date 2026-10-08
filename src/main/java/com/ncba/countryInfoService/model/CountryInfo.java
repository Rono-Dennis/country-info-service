package com.ncba.countryInfoService.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "country_info")
@Getter
@Setter
@NoArgsConstructor
public class CountryInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Natural key coming from the SOAP service (e.g. "KE"). Unique so repeated POSTs upsert. */
    @Column(name = "iso_code", nullable = false, unique = true, length = 10)
    private String isoCode;

    @Column(nullable = false)
    private String name;

    @Column(name = "capital_city")
    private String capitalCity;

    @Column(name = "phone_code", length = 20)
    private String phoneCode;

    @Column(name = "continent_code", length = 10)
    private String continentCode;

    @Column(name = "currency_iso_code", length = 10)
    private String currencyIsoCode;

    @Column(name = "country_flag", length = 500)
    private String countryFlag;

    @OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Language> languages = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public void replaceLanguages(List<Language> newLanguages) {
        languages.clear();
        newLanguages.forEach(this::addLanguage);
    }

    public void addLanguage(Language language) {
        language.setCountry(this);
        languages.add(language);
    }
}

