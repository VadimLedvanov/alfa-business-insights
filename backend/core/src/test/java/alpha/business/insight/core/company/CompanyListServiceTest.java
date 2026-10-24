package alpha.business.insight.core.company;

import alpha.business.insight.core.dto.CompanyListItem;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.repository.CompanyPaymentSummaryRepository;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.service.CompanyContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyListServiceTest {
    @Mock
    CompanyRepository companies;
    @Mock
    CompanyPaymentSummaryRepository payments;
    CompanyContextService service;

    @BeforeEach
    void setUp() {
        service = new CompanyContextService(companies, payments, 5);
    }

    @Test
    void givenFiltersWithSpaces_whenList_thenTrimFiltersAndPreservePage() {
        var pageable = PageRequest.of(1, 2, Sort.by("id"));
        var company = new CompanyListItem("abc", CompanyProfileStatus.AVAILABLE,
                "62", "ИТ", "Москва", "Москва");
        Page<CompanyListItem> page = new PageImpl<>(List.of(company), pageable, 3);
        when(companies.findCompanies("abc", "Москва", "62", pageable)).thenReturn(page);

        var result = service.getCompanies(" abc ", " Москва ", " 62 ", pageable);

        assertThat(result.getContent()).containsExactly(company);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void givenAbsentFilters_whenList_thenDoNotRestrictSearch(String filter) {
        var pageable = PageRequest.of(0, 20);
        var page = Page.<CompanyListItem>empty(pageable);
        when(companies.findCompanies(null, null, null, pageable)).thenReturn(page);

        assertThat(service.getCompanies(filter, filter, filter, pageable)).isSameAs(page);
    }

    @Test
    void givenSqlWildcards_whenList_thenSearchForLiteralCharacters() {
        var pageable = PageRequest.of(0, 20);
        var page = Page.<CompanyListItem>empty(pageable);
        when(companies.findCompanies("id!!!%!_", null, null, pageable)).thenReturn(page);

        assertThat(service.getCompanies("id!%_", null, null, pageable)).isSameAs(page);
    }

    @Test
    void givenPageBeyondLast_whenList_thenPreserveTotalElements() {
        var pageable = PageRequest.of(3, 2);
        Page<CompanyListItem> page = new PageImpl<>(List.of(), pageable, 3);
        when(companies.findCompanies("abc", null, null, pageable)).thenReturn(page);

        var result = service.getCompanies("abc", null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(3);
    }
}
