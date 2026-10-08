package com.ncba.countryInfoService.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

@Configuration
@EnableConfigurationProperties(SoapProperties.class)
public class SoapClientConfig {
    @Bean
    public Jaxb2Marshaller countryInfoMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan("com.ncba.countryInfoService.client.wsdl");
        return marshaller;
    }

    @Bean
    public WebServiceTemplate countryInfoWebServiceTemplate(Jaxb2Marshaller countryInfoMarshaller,
                                                            SoapProperties props) throws Exception {
        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.afterPropertiesSet();

        // Hard timeouts, never hold our request threads hostage
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(props.connectTimeout());
        sender.setReadTimeout(props.readTimeout());

        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(countryInfoMarshaller);
        template.setUnmarshaller(countryInfoMarshaller);
        template.setDefaultUri(props.endpoint());
        template.setMessageSender(sender);
        return template;
    }
}
