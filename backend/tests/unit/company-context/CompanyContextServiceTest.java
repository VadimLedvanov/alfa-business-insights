package alpha.business.insight.core.qa.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import alpha.business.insight.core.dto.CounterpartyPaymentTotals;
import alpha.business.insight.core.dto.GroupCompany;
import alpha.business.insight.core.model.Company;
import alpha.business.insight.core.model.CompanyProfileStatus;
import alpha.business.insight.core.qa.helpers.TestEvidence;
import alpha.business.insight.core.repository.CompanyPaymentSummaryRepository;
import alpha.business.insight.core.repository.CompanyRepository;
import alpha.business.insight.core.service.CompanyContextService;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Проверяет расчёты контекста компании на изолированных репозиториях. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Контекст компании")
@Tag("unit")
@Epic("Backend")
@Feature("Контекст компании")
@Tag("company-context")
class CompanyContextServiceTest {
    @Mock private CompanyRepository companies;
    @Mock private CompanyPaymentSummaryRepository payments;

    /** Проверяет доли контрагентов и ограничение числа записей в ответе. */
    @Test
    @DisplayName("TestUnitCalculatesCounterpartyShares")
    void TestUnitCalculatesCounterpartyShares() {
        // Подготавливает компанию, группу и обороты контрагентов.
        String companyId = "U_1";
        String groupId = "group-a";
        Company mainCompany = company(companyId, groupId, CompanyProfileStatus.AVAILABLE);
        List<GroupCompany> group =
                List.of(new GroupCompany(companyId, CompanyProfileStatus.AVAILABLE, "62.01", "ИТ"));
        List<CounterpartyPaymentTotals> turnover =
                List.of(
                        payment("U_2", "60", "100"),
                        payment("U_3", "30", "100"),
                        payment("U_4", "10", "100"));
        int limit = 2;
        when(companies.findById(companyId)).thenReturn(Optional.of(mainCompany));
        when(companies.findGroup(groupId)).thenReturn(group);
        when(payments.findCounterparties(companyId, 3)).thenReturn(turnover);

        Allure.step(
                "Рассчитать доли контрагентов и ограничить выдачу",
                () -> {
                    TestEvidence.input(
                            "Компания",
                            Map.of(
                                    "companyId",
                                    companyId,
                                    "groupId",
                                    groupId,
                                    "profileStatus",
                                    mainCompany.getProfileStatus().name()));
                    TestEvidence.input("Состав группы", group);
                    TestEvidence.input("Обороты контрагентов", turnover);
                    TestEvidence.input("Лимит ответа", limit);
                    TestEvidence.input(
                            "Окружение",
                            "unit: CompanyRepository and CompanyPaymentSummaryRepository mocked");
                    // Запрашивает рассчитанный контекст компании.
                    var result =
                            TestEvidence.capture(
                                    () ->
                                            new CompanyContextService(companies, payments, limit)
                                                    .getContext(companyId));
                    TestEvidence.output("Контекст компании", result);

                    // Проверяет состав выдачи, доли и концентрацию платежей.
                    assertThat(result.counterparties())
                            .as("контрагенты в пределах лимита %s", limit)
                            .extracting(c -> c.companyId())
                            .containsExactly("U_2", "U_3");
                    assertThat(result.totalPaymentAmount())
                            .as("полный оборот контрагентов")
                            .isEqualByComparingTo("100");
                    assertThat(result.counterparties())
                            .as("доли платежей в том же порядке")
                            .extracting(c -> c.paymentShare())
                            .containsExactly(
                                    new BigDecimal("0.60000000"), new BigDecimal("0.30000000"));
                    assertThat(result.concentration().top1Share())
                            .as("доля крупнейшего контрагента")
                            .isEqualByComparingTo("0.60000000");
                    assertThat(result.concentration().top3Share())
                            .as("доля трёх крупнейших контрагентов")
                            .isEqualByComparingTo("1.00000000");
                    verify(payments).findCounterparties(companyId, 3);
                });
    }

    /** Проверяет маркеры отсутствующих данных для компании без профиля и платежей. */
    @Test
    @DisplayName("TestUnitReportsMissingPayments")
    void TestUnitReportsMissingPayments() {
        // Подготавливает компанию без профиля и платежей.
        String companyId = "U_1";
        String groupId = " ";
        int limit = 5;
        CompanyProfileStatus status = CompanyProfileStatus.MISSING;
        Company missingCompany = company(companyId, groupId, status);
        List<CounterpartyPaymentTotals> noPayments = List.of();
        when(companies.findById(companyId)).thenReturn(Optional.of(missingCompany));
        when(payments.findCounterparties(companyId, limit)).thenReturn(noPayments);

        Allure.step(
                "Вернуть маркеры для компании без профиля и платежей",
                () -> {
                    TestEvidence.input(
                            "Компания",
                            Map.of(
                                    "companyId",
                                    companyId,
                                    "profileStatus",
                                    status.name(),
                                    "groupId",
                                    groupId));
                    TestEvidence.input("Обороты контрагентов", noPayments);
                    TestEvidence.input("Лимит контрагентов", limit);
                    TestEvidence.input(
                            "Окружение",
                            "unit: CompanyRepository and CompanyPaymentSummaryRepository mocked");
                    // Запрашивает контекст компании без данных.
                    var result =
                            TestEvidence.capture(
                                    () ->
                                            new CompanyContextService(companies, payments, limit)
                                                    .getContext(companyId));
                    TestEvidence.output("Контекст компании", result);

                    // Проверяет маркеры отсутствующих данных и пустые значения.
                    assertThat(result.group()).as("группа для пустого groupId").isEmpty();
                    assertThat(result.counterparties())
                            .as("список контрагентов без платежей")
                            .isEmpty();
                    assertThat(result.totalPaymentAmount()).as("оборот без платежей").isZero();
                    assertThat(result.concentration().top1Share())
                            .as("доля топ-1 при нулевом обороте")
                            .isNull();
                    assertThat(result.concentration().top3Share())
                            .as("доля топ-3 при нулевом обороте")
                            .isNull();
                    assertThat(result.missingData())
                            .as("признаки отсутствующих профиля и платежей")
                            .contains("profile.missing", "payments", "paymentShares");
                });
    }

    /** Проверяет ответ 404, если компания не найдена в репозитории. */
    @Test
    @DisplayName("TestUnitReturnsNotFound")
    void TestUnitReturnsNotFound() {
        // Настраивает репозиторий так, чтобы компания не была найдена.
        String companyId = "U_404";
        Optional<Company> company = Optional.empty();
        when(companies.findById(companyId)).thenReturn(company);

        Allure.step(
                "Вернуть 404 для отсутствующей компании",
                () -> {
                    TestEvidence.input("Идентификатор компании", companyId);
                    TestEvidence.input(
                            "Окружение", "unit: CompanyRepository returns Optional.empty");
                    // Запрашивает контекст отсутствующей компании.
                    Throwable actual =
                            catchThrowable(
                                    () ->
                                            new CompanyContextService(companies, payments, 1)
                                                    .getContext(companyId));
                    if (actual == null)
                        TestEvidence.output("Фактическое исключение", Map.of("exception", "none"));
                    else TestEvidence.exception(actual);

                    // Проверяет фактический статус ошибки.
                    assertThat(actual)
                            .isInstanceOf(ResponseStatusException.class)
                            .extracting("statusCode")
                            .isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /** Проверяет отказ конструктора при нулевом лимите контрагентов. */
    @Test
    @DisplayName("TestUnitRejectsInvalidLimit")
    void TestUnitRejectsInvalidLimit() {
        // Задаёт недопустимый лимит контрагентов.
        int limit = 0;
        Allure.step(
                "Отклонить нулевой лимит контрагентов",
                () -> {
                    TestEvidence.input("Лимит", limit);
                    TestEvidence.input(
                            "Окружение", "unit: constructor validation; repositories mocked");
                    // Создаёт сервис с нулевым лимитом.
                    Throwable actual =
                            catchThrowable(
                                    () -> new CompanyContextService(companies, payments, limit));
                    if (actual == null)
                        TestEvidence.output("Фактическое исключение", Map.of("exception", "none"));
                    else TestEvidence.exception(actual);

                    // Проверяет отказ конструктора.
                    assertThat(actual).isInstanceOf(IllegalArgumentException.class);
                });
    }

    /** Создаёт изолированную компанию с заданными идентификаторами и статусом. */
    private static Company company(String id, String group, CompanyProfileStatus status) {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(id);
        when(company.getGroupId()).thenReturn(group);
        when(company.getProfileStatus()).thenReturn(status);
        return company;
    }

    /** Создаёт сводные данные платежей контрагента из десятичных строк. */
    private static CounterpartyPaymentTotals payment(String id, String amount, String all) {
        return new CounterpartyPaymentTotals(
                id,
                "AVAILABLE",
                "62.01",
                "ИТ",
                BigDecimal.ZERO,
                new BigDecimal(amount),
                new BigDecimal(amount),
                new BigDecimal(all));
    }
}
