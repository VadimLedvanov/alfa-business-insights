package alpha.business.insight.core.controller;

import alpha.business.insight.core.dto.CompanyListItem;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.service.CompanyContextService;
import alpha.business.insight.core.service.InsightService;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {
    @Mock
    CompanyContextService service;
    @Mock
    InsightService insights;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CompanyController(service, insights))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void givenNoParameters_whenList_thenUseDefaultPageAndSort() throws Exception {
        var pageable = PageRequest.of(0, 20, Sort.by("id"));
        when(service.getCompanies(null, null, null, pageable)).thenReturn(Page.empty(pageable));

        mvc.perform(get("/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void givenFiltersAndPage_whenList_thenBindParametersAndReturnCompactDto() throws Exception {
        var pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "id"));
        var company = new CompanyListItem("company_1", CompanyProfileStatus.AVAILABLE,
                "62", "ИТ", "Москва", "Москва");
        when(service.getCompanies("company", "Москва", "62", pageable))
                .thenReturn(new PageImpl<>(List.of(company), pageable, 3));

        mvc.perform(get("/companies").param("companyId", "company").param("city", "Москва")
                        .param("mainIndustryCode", "62").param("page", "1").param("size", "2")
                        .param("sort", "id,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].companyId").value("company_1"))
                .andExpect(jsonPath("$.content[0].profileStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.content[0].mainIndustryCode").value("62"))
                .andExpect(jsonPath("$.content[0].mainIndustryName").value("ИТ"))
                .andExpect(jsonPath("$.content[0].city").value("Москва"))
                .andExpect(jsonPath("$.content[0].region").value("Москва"))
                .andExpect(jsonPath("$.content[0].length()").value(6));
    }
}
