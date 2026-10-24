package alpha.business.insight.core.qa.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import alpha.business.insight.core.dto.CompanyListItem;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.repository.CompanyPaymentSummaryRepository;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.service.CompanyContextService;
import io.qameta.allure.Allure;
import io.qameta.allure.Step;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

/** Проверяет фильтрацию и пагинацию списка компаний на изолированных репозиториях. */
@ExtendWith(MockitoExtension.class)
@Tag("unit")
@Tag("company-context")
class CompanyListServiceTest {
    @Mock private CompanyRepository companies;
    @Mock private CompanyPaymentSummaryRepository payments;

    private CompanyContextService service;

    /** Создаёт сервис с тем же лимитом, что применяется по умолчанию. */
    @BeforeEach
    void setUp() {
        service = new CompanyContextService(companies, payments, 5);
    }

    /** Удаляет пробелы из фильтров и сохраняет метаданные страницы. */
    @Test
    @DisplayName("TestUnitTrimsCompanyListFiltersAndPreservesPage")
    @Step("Удаляет пробелы из фильтров списка компаний")
    void TestUnitTrimsCompanyListFiltersAndPreservesPage() {
        Allure.step(
                "1. Удалить пробелы из фильтров и сохранить метаданные страницы",
                () -> {
                    // Подготовка
                    var pageable = PageRequest.of(1, 2, Sort.by("id"));
                    var company =
                            new CompanyListItem(
                                    "abc",
                                    CompanyProfileStatus.AVAILABLE,
                                    "62",
                                    "ИТ",
                                    "Москва",
                                    "Москва");
                    Page<CompanyListItem> page = new PageImpl<>(List.of(company), pageable, 3);
                    when(companies.findCompanies("abc", "Москва", "62", pageable)).thenReturn(page);

                    // Действие
                    var result = service.getCompanies(" abc ", " Москва ", " 62 ", pageable);

                    // Проверки
                    assertThat(result.getContent())
                            .as("компании на запрошенной странице")
                            .containsExactly(company);
                    assertThat(result.getNumber()).as("номер страницы").isEqualTo(1);
                    assertThat(result.getSize()).as("размер страницы").isEqualTo(2);
                    assertThat(result.getTotalElements()).as("общее число компаний").isEqualTo(3);
                    assertThat(result.getTotalPages()).as("общее число страниц").isEqualTo(2);
                });
    }

    /** Не ограничивает поиск, если все фильтры отсутствуют или пусты. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("TestUnitIgnoresAbsentCompanyListFilters")
    @Step("Игнорирует отсутствующие фильтры списка компаний")
    void TestUnitIgnoresAbsentCompanyListFilters(String filter) {
        Allure.step(
                "1. Игнорировать отсутствующий или пустой фильтр и вернуть страницу",
                () -> {
                    // Подготовка
                    var pageable = PageRequest.of(0, 20);
                    var page = Page.<CompanyListItem>empty(pageable);
                    when(companies.findCompanies(null, null, null, pageable)).thenReturn(page);

                    // Действие и проверки
                    assertThat(service.getCompanies(filter, filter, filter, pageable))
                            .as("страница без ограничивающих фильтров")
                            .isSameAs(page);
                });
    }

    /** Ищет символы SQL-шаблона как буквальный текст. */
    @Test
    @DisplayName("TestUnitTreatsCompanyListWildcardsLiterally")
    @Step("Ищет SQL-шаблоны как буквальные символы")
    void TestUnitTreatsCompanyListWildcardsLiterally() {
        Allure.step(
                "1. Экранировать специальные символы фильтра идентификатора компании",
                () -> {
                    // Подготовка
                    var pageable = PageRequest.of(0, 20);
                    var page = Page.<CompanyListItem>empty(pageable);
                    when(companies.findCompanies("id!!!%!_", null, null, pageable))
                            .thenReturn(page);

                    // Действие и проверки
                    assertThat(service.getCompanies("id!%_", null, null, pageable))
                            .as("страница по идентификатору с буквальными шаблонными символами")
                            .isSameAs(page);
                });
    }

    /** Сохраняет общее число результатов для страницы за пределами выдачи. */
    @Test
    @DisplayName("TestUnitPreservesCompanyCountBeyondLastPage")
    @Step("Сохраняет общее число результатов за последней страницей")
    void TestUnitPreservesCompanyCountBeyondLastPage() {
        Allure.step(
                "1. Сохранить общее число компаний для страницы за пределами выдачи",
                () -> {
                    // Подготовка
                    var pageable = PageRequest.of(3, 2);
                    Page<CompanyListItem> page = new PageImpl<>(List.of(), pageable, 3);
                    when(companies.findCompanies("abc", null, null, pageable)).thenReturn(page);

                    // Действие
                    var result = service.getCompanies("abc", null, null, pageable);

                    // Проверки
                    assertThat(result.getContent())
                            .as("содержимое страницы за пределами выдачи")
                            .isEmpty();
                    assertThat(result.getTotalElements())
                            .as("общее число найденных компаний")
                            .isEqualTo(3);
                });
    }
}
