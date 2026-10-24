package alpha.business.insight.core.qa.company;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;

import alpha.business.insight.core.controller.CompanyController;
import alpha.business.insight.core.dto.CompanyListItem;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.qa.connectors.MockMvcEvidenceResultHandler;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.service.CompanyContextService;
import alpha.business.insight.core.service.InsightService;
import io.qameta.allure.Step;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Проверяет привязку параметров списка компаний в MVC-контроллере. */
@ExtendWith(MockitoExtension.class)
@Tag("integration")
@Tag("company-context")
class CompanyControllerTest {
    @Mock private CompanyContextService service;
    @Mock private InsightService insights;

    private MockMvc mvc;

    /** Собирает изолированный MVC-контроллер и сохраняет HTTP evidence. */
    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new CompanyController(service, insights))
                        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                        .alwaysDo(new MockMvcEvidenceResultHandler())
                        .build();
    }

    /** Использует значения страницы по умолчанию и сортировку по идентификатору. */
    @Test
    @DisplayName("TestIntegrationCompanyListUsesDefaultPageAndSort")
    @Step("Использует настройки страницы списка компаний по умолчанию")
    void TestIntegrationCompanyListUsesDefaultPageAndSort() throws Exception {
        // Подготовка
        var pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(service.getCompanies(null, null, null, pageable)).thenReturn(Page.empty(pageable));

        // Действие и проверки
        request()
                .when()
                .get("/api/companies")
                .then()
                .statusCode(200)
                .body("content", empty())
                .body("number", equalTo(0))
                .body("size", equalTo(20))
                .body("totalElements", equalTo(0));
    }

    /** Передаёт фильтры и пагинацию сервису, возвращая компактный DTO компании. */
    @Test
    @DisplayName("TestIntegrationCompanyListBindsFiltersAndReturnsCompactDto")
    @Step("Связывает фильтры и пагинацию списка компаний")
    void TestIntegrationCompanyListBindsFiltersAndReturnsCompactDto() throws Exception {
        // Подготовка
        var pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "id"));
        var company =
                new CompanyListItem(
                        "company_1",
                        CompanyProfileStatus.AVAILABLE,
                        "62",
                        "ИТ",
                        "Москва",
                        "Москва");
        when(service.getCompanies("company", "Москва", "62", pageable))
                .thenReturn(new PageImpl<>(List.of(company), pageable, 3));

        // Действие и проверки
        request()
                .queryParam("companyId", "company")
                .queryParam("city", "Москва")
                .queryParam("mainIndustryCode", "62")
                .queryParam("page", "1")
                .queryParam("size", "2")
                .queryParam("sort", "id,desc")
                .when()
                .get("/api/companies")
                .then()
                .statusCode(200)
                .body("number", equalTo(1))
                .body("size", equalTo(2))
                .body("totalElements", equalTo(3))
                .body("totalPages", equalTo(2))
                .body("content", hasSize(1))
                .body("content[0].companyId", equalTo("company_1"))
                .body("content[0].profileStatus", equalTo("AVAILABLE"))
                .body("content[0].mainIndustryCode", equalTo("62"))
                .body("content[0].mainIndustryName", equalTo("ИТ"))
                .body("content[0].city", equalTo("Москва"))
                .body("content[0].region", equalTo("Москва"))
                .body("content[0].size()", equalTo(6));
    }

    /** Создаёт request-local спецификацию с общим API-префиксом. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }
}
