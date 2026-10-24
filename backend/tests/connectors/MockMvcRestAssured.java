package alpha.business.insight.core.qa.connectors;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;

import io.restassured.config.JsonConfig;
import io.restassured.module.mockmvc.config.RestAssuredMockMvcConfig;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import io.restassured.path.json.config.JsonPathConfig;
import org.springframework.test.web.servlet.MockMvc;

/** Создаёт локальную спецификацию REST Assured для переданного экземпляра MockMvc. */
public final class MockMvcRestAssured {
    /** Создаёт request-local MockMvc спецификацию с Double JSON numbers и общим API-префиксом. */
    public static MockMvcRequestSpecification request(MockMvc mvc) {
        RestAssuredMockMvcConfig config =
                new RestAssuredMockMvcConfig()
                        .jsonConfig(
                                new JsonConfig()
                                        .numberReturnType(JsonPathConfig.NumberReturnType.DOUBLE));
        return given().config(config)
                .mockMvc(mvc)
                .postProcessors(
                        request -> {
                            request.setContextPath("/api");
                            return request;
                        });
    }

    /** Запрещает создание экземпляров статического помощника. */
    private MockMvcRestAssured() {}
}
