package alpha.business.insight.core.qa.company;

import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет контекст компании и расчёты по двум связанным компаниям через HTTP. */
@Epic("Backend")
@Feature("Контекст компании")
@Story("Рассчитать профиль, группу и платежи через HTTP")
@Tag("e2e")
@Tag("smoke")
@Tag("company-context")
class CompanyContextSmokeTest extends RealStackE2eTest {
    private final String companyId = "smoke_" + UUID.randomUUID().toString().replace("-", "");
    private final String counterpartyId = companyId + "_partner";
    private final String groupId = companyId + "_group";

    /** Создаёт данные компании, выполняет HTTP-чтение и сверяет расчётные поля. */
    @Test
    @DisplayName("TestSmokeCompanyContextCalculations")
    void TestSmokeCompanyContextCalculations() throws Exception {
        Allure.step("Подготовить компанию, группу и платежи", () -> {
            jdbc.update("""
                    INSERT INTO companies (company_id, profile_status, main_industry_code, main_industry_name,
                        region, revenue, group_id, has_foreign_trade, has_loan)
                    VALUES (?, 'AVAILABLE', '62', 'Информационные технологии', 'Москва', 15000000000,
                        ?, true, false), (?, 'AVAILABLE', '10', 'Пищевые продукты', ?, 9000000000,
                        ?, false, true)
                    """, companyId, groupId, counterpartyId, "Санкт-Петербург", groupId);
            jdbc.update("""
                    INSERT INTO company_payment_summaries
                        (payer_company_id, recipient_company_id, total_payment_amount, payment_count)
                    VALUES (?, ?, 300, 2), (?, ?, 200, 1)
                    """, companyId, counterpartyId, counterpartyId, companyId);
        });

        Allure.step("Проверить доступ директора и контекст компании", () -> {
            // Подготовить связь доступа для директора.
            String directorToken = keycloak.accessToken("director", "director-dev");
            String directorId = json(backend.get("/auth/me", directorToken)).path("id").asText();
            jdbc.update("INSERT INTO user_company_access (user_id, company_id) VALUES (?, ?)",
                    directorId, companyId);

            // Действие
            var response = backend.get("/companies/" + companyId, directorToken);
            assertThat(response.statusCode()).isEqualTo(200);
            var context = json(response);

            // Проверка
            assertThat(context.at("/profile/companyId").asText()).isEqualTo(companyId);
            assertThat(context.at("/profile/mainIndustryCode").asText()).isEqualTo("62");
            assertThat(context.at("/group").size()).isEqualTo(2);
            assertThat(context.at("/counterparties").get(0).path("companyId").asText()).isEqualTo(counterpartyId);
            assertThat(context.at("/counterparties/0/totalPaymentAmount").decimalValue()).isEqualByComparingTo("500");
            assertThat(context.at("/counterparties/0/paymentShare").decimalValue()).isEqualByComparingTo("1");
            assertThat(context.at("/totalPaymentAmount").decimalValue()).isEqualByComparingTo("500");
        });
    }

    /** Удаляет тестовые связи доступа, платежи и компании. */
    @AfterEach
    void removeSyntheticRows() {
        jdbc.update("DELETE FROM user_company_access WHERE company_id IN (?, ?)", companyId, counterpartyId);
        jdbc.update("DELETE FROM company_payment_summaries WHERE payer_company_id = ? OR recipient_company_id = ?",
                companyId, companyId);
        jdbc.update("DELETE FROM companies WHERE company_id IN (?, ?)", companyId, counterpartyId);
    }
}
