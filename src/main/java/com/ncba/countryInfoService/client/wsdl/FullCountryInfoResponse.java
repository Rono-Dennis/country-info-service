package com.ncba.countryInfoService.client.wsdl;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "FullCountryInfoResponse")
public class FullCountryInfoResponse {

    protected TCountryInfo FullCountryInfoResult;
}
