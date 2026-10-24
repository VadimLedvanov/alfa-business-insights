package alpha.business.insight.core.qa.connectors;

import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import org.springframework.test.web.servlet.MockMvc;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;

/** Создаёт локальную спецификацию REST Assured для переданного экземпляра MockMvc. */
public final class MockMvcRestAssured {
    /** Запускает запросы через существующий MockMvc с общим префиксом API. */
    public static MockMvcRequestSpecification request(MockMvc mvc) {
        return given().mockMvc(mvc).postProcessors(request -> {
            request.setContextPath("/api");
            return request;
        });
    }

    /** Запрещает создание экземпляров статического помощника. */
    private MockMvcRestAssured() {
    }
}
