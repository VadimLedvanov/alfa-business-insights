package alpha.business.insight.core.qa.connectors;

import io.qameta.allure.Allure;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Прикрепляет фактический обмен через MockMvc к активному шагу Allure. */
public final class MockMvcEvidenceResultHandler implements ResultHandler {
    /** Прикрепляет сведения о фактическом запросе и ответе к Allure. */
    @Override
    public void handle(MvcResult result) throws Exception {
        MockHttpServletRequest request = result.getRequest();
        String path = request.getRequestURI();
        if (request.getQueryString() != null) path += "?" + request.getQueryString();
        Allure.step(request.getMethod() + " " + HttpEvidence.redact(path) + " — MockMvc in-process exchange", () -> {
            attachRequest(request);
            attachResponse(result);
        });
    }

    /** Сохраняет в Allure фактические заголовки, параметры и тело запроса. */
    private static void attachRequest(MockHttpServletRequest request) throws Exception {
        String contentType = request.getContentType() == null ? "" : request.getContentType();
        byte[] body = request.getContentAsByteArray();
        StringBuilder summary = new StringBuilder(request.getMethod()).append(' ')
                .append(request.getRequestURL());
        if (request.getQueryString() != null) summary.append('?').append(request.getQueryString());
        summary.append("\nHeaders:\n").append(HttpEvidence.headers(requestHeaders(request)))
                .append("\nParameters:\n");
        new TreeMap<>(request.getParameterMap()).forEach((name, values) -> summary
                .append(HttpEvidence.formField(name, values)).append('\n'));
        summary.append("Body:\n");

        if (request instanceof MockMultipartHttpServletRequest multipart) {
            appendMultipartSummary(summary, multipart);
            Allure.addAttachment("MockMvc request", "text/plain", HttpEvidence.redact(summary.toString()));
            attachMultipart(multipart);
        } else {
            appendBodySummary(summary, contentType, body);
            Allure.addAttachment("MockMvc request", "text/plain", HttpEvidence.redact(summary.toString()));
            if (body != null && body.length > 0) attachBody("MockMvc request body", contentType, body);
        }
    }

    /** Сохраняет в Allure фактический статус, заголовки и тело ответа. */
    private static void attachResponse(MvcResult result) throws Exception {
        var response = result.getResponse();
        String contentType = response.getContentType() == null ? "application/octet-stream" : response.getContentType();
        byte[] body = response.getContentAsByteArray();
        StringBuilder summary = new StringBuilder("HTTP ").append(response.getStatus())
                .append("\nHeaders:\n").append(HttpEvidence.headers(responseHeaders(response)))
                .append("\nBody:\n");
        appendBodySummary(summary, contentType, body);
        Allure.addAttachment("MockMvc response", "text/plain", HttpEvidence.redact(summary.toString()));
        if (body != null && body.length > 0) attachBody("MockMvc response body", contentType, body);
    }

    /** Добавляет к сводке имена и размеры multipart-файлов. */
    private static void appendMultipartSummary(StringBuilder summary, MockMultipartHttpServletRequest request) {
        summary.append("Multipart form fields:\n");
        new TreeMap<>(request.getParameterMap()).forEach((name, values) -> summary
                .append(HttpEvidence.formField(name, values)).append('\n'));
        summary.append("Multipart files:\n");
        new TreeMap<>(request.getMultiFileMap()).forEach((name, files) -> files.forEach(file -> summary
                .append(name).append(": filename=").append(file.getOriginalFilename())
                .append(", contentType=").append(file.getContentType())
                .append(", bytes=").append(file.getSize()).append('\n')));
    }

    /** Прикрепляет поля формы и содержимое multipart-файлов к Allure. */
    private static void attachMultipart(MockMultipartHttpServletRequest request) throws Exception {
        new TreeMap<>(request.getParameterMap()).forEach((name, values) -> Allure.addAttachment(
                "MockMvc multipart field " + name, "text/plain",
                HttpEvidence.formField(name, values)));
        int index = 0;
        for (Map.Entry<String, List<MultipartFile>> entry : new TreeMap<>(request.getMultiFileMap()).entrySet()) {
            for (MultipartFile file : entry.getValue()) {
                String filename = file.getOriginalFilename() == null ? "part" : file.getOriginalFilename();
                String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
                attachBody("MockMvc multipart file " + (++index) + " " + filename,
                        contentType, file.getBytes());
            }
        }
    }

    /** Формирует сводку тела без вывода бинарного содержимого в текст. */
    private static void appendBodySummary(StringBuilder summary, String contentType, byte[] body) throws Exception {
        if (body == null || body.length == 0) {
            summary.append("(empty body)\n");
        } else if (isText(contentType)) {
            summary.append(body.length).append(" bytes; see text body attachment\n");
        } else {
            summary.append(body.length).append(" bytes; SHA-256=").append(sha256(body))
                    .append("; see binary body attachment\n");
        }
    }

    /** Добавляет текстовое или бинарное тело с учётом его типа содержимого. */
    private static void attachBody(String name, String contentType, byte[] body) throws Exception {
        if (isText(contentType)) {
            Allure.addAttachment(name, attachmentContentType(contentType),
                    HttpEvidence.redact(new String(body, charset(contentType))));
            return;
        }
        String extension = contentType.toLowerCase(java.util.Locale.ROOT).contains("pdf") ? ".pdf" : ".bin";
        Allure.addAttachment(name + " (" + body.length + " bytes; SHA-256=" + sha256(body) + ")",
                contentType, new ByteArrayInputStream(body), extension);
    }

    /** Собирает заголовки запроса в порядке без учёта регистра. */
    private static Map<String, List<String>> requestHeaders(MockHttpServletRequest request) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            Enumeration<String> headerValues = request.getHeaders(name);
            while (headerValues.hasMoreElements()) values.add(headerValues.nextElement());
            headers.put(name, values);
        }
        return headers;
    }

    /** Собирает заголовки ответа в порядке без учёта регистра. */
    private static Map<String, List<String>> responseHeaders(org.springframework.mock.web.MockHttpServletResponse response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.getHeaderNames().forEach(name -> headers.put(name, response.getHeaders(name).stream().toList()));
        return headers;
    }

    /** Определяет, можно ли представить тело ответа как текст. */
    private static boolean isText(String contentType) {
        String type = contentType.toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("text/") || type.contains("json") || type.contains("xml")
                || type.contains("yaml") || type.contains("x-www-form-urlencoded");
    }

    /** Выбирает тип для текстового вложения Allure. */
    private static String attachmentContentType(String contentType) {
        String type = contentType.toLowerCase(java.util.Locale.ROOT);
        return type.contains("json") ? "application/json" : "text/plain";
    }

    /** Возвращает кодировку из типа содержимого или UTF-8 по умолчанию. */
    private static Charset charset(String contentType) {
        try {
            var parsed = org.springframework.http.MediaType.parseMediaType(contentType).getCharset();
            return parsed == null ? StandardCharsets.UTF_8 : parsed;
        } catch (IllegalArgumentException ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Вычисляет SHA-256 для идентификации бинарного вложения. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
