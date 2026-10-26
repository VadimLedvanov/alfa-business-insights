package alpha.business.insight.core.qa.documents;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import alpha.business.insight.core.config.SecurityConfig;
import alpha.business.insight.core.controller.DocumentController;
import alpha.business.insight.core.ml.DocumentAnalyzer;
import alpha.business.insight.core.model.KnowledgeDocument;
import alpha.business.insight.core.qa.connectors.MockMvcRestAssured;
import alpha.business.insight.core.repository.DocumentRepository;
import alpha.business.insight.core.service.DocumentService;
import alpha.business.insight.core.service.DocumentStorage;
import alpha.business.insight.core.service.DocumentVectorStore;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Проверяет список сохранённых PDF, страницы и доступ администратора без внешних сервисов. */
@WebMvcTest(
        controllers = DocumentController.class,
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/test-realm",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/test-realm/certs"
        })
@Import({SecurityConfig.class, DocumentService.class})
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class
})
@Tag("integration")
@Tag("documents")
class DocumentListControllerTest {
    private static final UUID ID = UUID.fromString("e8350dc9-332d-4395-af32-9c084a3aa240");

    @Autowired MockMvc mvc;
    @MockitoBean DocumentRepository repository;
    @MockitoBean DocumentStorage storage;
    @MockitoBean DocumentAnalyzer analyzer;
    @MockitoBean DocumentVectorStore vectors;
    @MockitoBean JwtDecoder decoder;

    /** Возвращает пустую первую страницу и устойчивый порядок от новых документов к старым. */
    @Test
    void listDefaultsToNewestDocumentsFirst() {
        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "uploadedAt", "id"));
        when(repository.findAll(pageable)).thenReturn(Page.empty(pageable));

        adminRequest()
                .when()
                .get("/api/admin/documents")
                .then()
                .statusCode(200)
                .body("content", empty())
                .body("number", equalTo(0))
                .body("size", equalTo(20))
                .body("totalElements", equalTo(0));
    }

    /** Передаёт страницу и сортировку в хранилище, возвращает метаданные без пути на сервере. */
    @Test
    void listReturnsRequestedPageAndPublicMetadata() {
        var pageable = PageRequest.of(1, 2, Sort.by("title"));
        var document =
                new KnowledgeDocument(
                        ID,
                        "Обзор рынка",
                        "report.pdf",
                        "private/server/path.pdf",
                        123,
                        "a".repeat(64),
                        Instant.parse("2026-10-24T12:00:00Z"),
                        null,
                        3,
                        1,
                        "stub-v1",
                        8,
                        true);
        when(repository.findAll(any(Pageable.class)))
                .thenAnswer(
                        invocation ->
                                new PageImpl<>(List.of(document), invocation.getArgument(0), 3));

        adminRequest()
                .queryParam("page", 1)
                .queryParam("size", 2)
                .queryParam("sort", "title,asc")
                .when()
                .get("/api/admin/documents")
                .then()
                .statusCode(200)
                .body("number", equalTo(1))
                .body("size", equalTo(2))
                .body("totalElements", equalTo(3))
                .body("totalPages", equalTo(2))
                .body("content", hasSize(1))
                .body("content[0].documentId", equalTo(ID.toString()))
                .body("content[0].title", equalTo("Обзор рынка"))
                .body("content[0].fileName", equalTo("report.pdf"))
                .body("content[0].sizeBytes", equalTo(123))
                .body("content[0].chunkCount", equalTo(3))
                .body("content[0]", not(hasKey("filePath")));
    }

    /** Без токена список документов недоступен. */
    @Test
    void listRequiresAuthentication() {
        request().when().get("/api/admin/documents").then().statusCode(401);
    }

    /** Директор не получает административный список документов. */
    @Test
    void listRejectsCompanyDirector() {
        request()
                .postProcessors(
                        jwt().authorities(new SimpleGrantedAuthority("ROLE_COMPANY_DIRECTOR")))
                .when()
                .get("/api/admin/documents")
                .then()
                .statusCode(403);
    }

    /** Публикует список и параметры страниц в Swagger. */
    @Test
    void openApiDescribesDocumentListAndPagination() {
        request()
                .when()
                .get("/api/v3/api-docs")
                .then()
                .statusCode(200)
                .body("paths['/admin/documents'].get.summary", notNullValue())
                .body("paths['/admin/documents'].get.responses['200']", notNullValue())
                .body(
                        "paths['/admin/documents'].get.parameters.find { it.name == 'page' }",
                        notNullValue())
                .body(
                        "paths['/admin/documents'].get.parameters.find { it.name == 'size' }",
                        notNullValue())
                .body(
                        "paths['/admin/documents'].get.parameters.find { it.name == 'sort' }",
                        notNullValue());
    }

    /** Создаёт запрос с ролью системного администратора. */
    private MockMvcRequestSpecification adminRequest() {
        return request()
                .postProcessors(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")));
    }

    /** Создаёт спецификацию с контекстом API без сетевого соединения. */
    private MockMvcRequestSpecification request() {
        return MockMvcRestAssured.request(mvc);
    }
}
