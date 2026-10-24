package alpha.business.insight.core.qa.documents;

import alpha.business.insight.core.qa.helpers.SyntheticPdf;
import alpha.business.insight.core.qa.support.RealStackE2eTest;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет живой HTTP-доступ к документам на реальном стеке приложения. */
@Epic("Backend")
@Feature("Документы аналитики банка")
@Tag("e2e")
@Tag("documents")
class DocumentUploadHttpE2eTest extends RealStackE2eTest {
    /** Проверяет реальные запреты на загрузку и анонимное чтение документа. */
    @Test
    @Story("Запретить запись документа пользователю без роли администратора и чтение анониму")
    @DisplayName("TestE2EDocumentAccessRules")
    void TestE2EDocumentAccessRules() throws Exception {
        // Подготовка
        var uploadFixture = SyntheticPdf.multipartUpload();

        Allure.step("Проверить, что директор не может загружать документы", () -> {
            // Подготовка
            var directorToken = keycloak.accessToken("director", "director-dev");
            var upload = backend.postMultipart("/admin/documents", uploadFixture, directorToken);

            // Проверка
            assertThat(upload.statusCode()).isEqualTo(403);
        });
        Allure.step("Проверить, что анонимный пользователь не может читать документы", () -> {
            // Действие
            var anonymousRead = backend.get("/documents/" + UUID.randomUUID(), null);

            // Проверка
            assertThat(anonymousRead.statusCode()).isEqualTo(401);
        });
    }
}
