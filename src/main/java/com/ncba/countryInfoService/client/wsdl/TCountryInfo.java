package com.ncba.countryInfoService.client.wsdl;

import jakarta.xml.bind.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "tCountryInfo")
public class TCountryInfo {

    protected String sISOCode;
    protected String sName;
    protected String sCapitalCity;
    protected String sPhoneCode;
    protected String sContinentCode;
    protected String sCurrencyISOCode;
    protected String sCountryFlag;

    @XmlElementWrapper(name = "Languages")
    @XmlElement(name = "tLanguage")
    protected List<TLanguage> languages = new ArrayList<>();
}
