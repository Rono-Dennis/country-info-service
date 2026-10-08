package com.ncba.countryInfoService;

import com.ncba.countryInfoService.client.CountrySoapClient;
import com.ncba.countryInfoService.client.wsdl.CountryISOCode;
import com.ncba.countryInfoService.client.wsdl.TCountryInfo;
import com.ncba.countryInfoService.client.wsdl.TLanguage;
import com.ncba.countryInfoService.repository.CountryInfoRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class CountryInfoServiceApplicationTests {
	private static final String NS = "http://www.oorsprong.org/websamples.countryinfo";
	private static Jaxb2Marshaller marshaller;

	@MockitoBean
    CountrySoapClient soapClient;

	@Autowired
	MockMvc mvc;

	@Autowired
    CountryInfoRepository repository;

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	@Test
	void contextLoads() {
	}


	@BeforeAll
	static void setUp() throws Exception {
		marshaller = new Jaxb2Marshaller();
		marshaller.setPackagesToScan("com.ncba.countryInfoService.client.wsdl");
		marshaller.afterPropertiesSet();
	}

	@Test
	void marshalsIsoCodeRequestInTheServiceNamespace() {
		CountryISOCode request = new CountryISOCode();
		request.setSCountryName("Kenya");

		StringWriter out = new StringWriter();
		marshaller.marshal(request, new StreamResult(out));

		System.out.println("output: "+out.toString());
		assertThat(out.toString())
				.contains("xmlns=\"" + NS + "\"")
				.contains("<sCountryName>Kenya</sCountryName>");
	}

	@Test
	void postConvertsToSentenceCaseCallsSoapAndStores() throws Exception {
		testKenya();

		mvc.perform(post("/api/v1/countries")
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"kenya\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.isoCode").value("KE"))
				.andExpect(jsonPath("$.name").value("Kenya"))
				.andExpect(jsonPath("$.capitalCity").value("Nairobi"))
				.andExpect(jsonPath("$.languages[0].name").value("Swahili"));

		verify(soapClient).getIsoCode("Kenya");   // sentence-cased before the SOAP call
		assertThat(repository.count()).isEqualTo(1);
	}

	private void testKenya() {
		when(soapClient.getIsoCode("Kenya")).thenReturn("KE");
		when(soapClient.getFullInfo("KE")).thenReturn(kenya());
	}

	private static TCountryInfo kenya() {
		TCountryInfo info = new TCountryInfo();
		info.setSISOCode("KE");
		info.setSName("Kenya");
		info.setSCapitalCity("Nairobi");
		info.setSPhoneCode("254");
		info.setSContinentCode("AF");
		info.setSCurrencyISOCode("KES");
		info.setSCountryFlag("http://flags/Kenya.jpg");
		TLanguage swahili = new TLanguage();
		swahili.setSISOCode("swa");
		swahili.setSName("Swahili");
		info.getLanguages().add(swahili);
		return info;
	}

	@Test
	void postSameCountryTwiceDoesNotDuplicate() throws Exception {
		createKenya();
		mvc.perform(post("/api/v1/countries")
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"KENYA\"}"))
				.andExpect(status().isOk());
		assertThat(repository.count()).isEqualTo(1);
	}

	private long createKenya() throws Exception {
		testKenya();
		MvcResult res = mvc.perform(post("/api/v1/countries")
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"kenya\"}"))
				.andExpect(status().isCreated()).andReturn();
		String body = res.getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
	}

	@Test
	void crudLifecycle() throws Exception {
		long id = createKenya();

		mvc.perform(get("/api/v1/countries/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isoCode").value("KE"));

		mvc.perform(get("/api/v1/countries"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].name").value("Kenya"))
				.andExpect(jsonPath("$.page.totalElements").value(1));

		mvc.perform(put("/api/v1/countries/{id}", id)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"capitalCity\":\"Nairobi City\",\"languages\":[{\"isoCode\":\"eng\",\"name\":\"English\"}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.capitalCity").value("Nairobi City"))
				.andExpect(jsonPath("$.name").value("Kenya"))
				.andExpect(jsonPath("$.languages.length()").value(1))
				.andExpect(jsonPath("$.languages[0].name").value("English"));

		mvc.perform(delete("/api/v1/countries/{id}", id)).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/countries/{id}", id)).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/countries/{id}", id)).andExpect(status().isNotFound());
		mvc.perform(put("/api/v1/countries/{id}", id)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}
}
