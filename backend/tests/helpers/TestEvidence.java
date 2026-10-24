package alpha.business.insight.core.qa.helpers;

import alpha.business.insight.core.qa.connectors.HttpEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

/** Добавляет в шаги Allure читаемые снимки фактических данных с редактированием секретов. */
public final class TestEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SENSITIVE_FIELDS =
            Set.of(
                    "password",
                    "access_token",
                    "refresh_token",
                    "id_token",
                    "client_secret",
                    "authorization",
                    "proxy-authorization",
                    "cookie",
                    "set-cookie");

    /** Запрещает создавать экземпляры утилиты для сбора данных Allure. */
    private TestEvidence() {}

    /** Записывает входные данные, фактический результат и проверяет его внутри шага Allure. */
    public static <T> T step(
            String name,
            List<Input> inputs,
            ThrowingSupplier<T> operation,
            ThrowingConsumer<T> assertions) {
        return Allure.step(
                name,
                () -> {
                    for (Input input : inputs) input(input.name(), input.value());
                    T actual;
                    try {
                        actual = operation.get();
                    } catch (Throwable failure) {
                        exception(failure);
                        throw failure;
                    }
                    output("Actual result", actual);
                    try {
                        assertions.accept(actual);
                    } catch (Throwable failure) {
                        exception(failure);
                        throw failure;
                    }
                    return actual;
                });
    }

    /** Записывает входы и фактический исход операции, затем проверяет ожидаемую ошибку. */
    public static void stepThrows(
            String name,
            List<Input> inputs,
            ThrowingRunnable operation,
            ThrowingConsumer<Throwable> assertions) {
        Allure.step(
                name,
                () -> {
                    for (Input input : inputs) input(input.name(), input.value());
                    Throwable actual = null;
                    try {
                        operation.run();
                    } catch (Throwable failure) {
                        actual = failure;
                    }
                    if (actual == null) {
                        output(
                                "Actual operation outcome",
                                Map.of("exception", "none", "completed", true));
                    } else {
                        exception(actual);
                    }
                    assertions.accept(actual);
                    if (actual == null)
                        throw new AssertionError(
                                "Expected an exception, but the operation completed normally");
                });
    }

    /** Прикладывает снимок фактического входного значения. */
    public static void input(String name, Object actualValue) {
        attach("Input: " + name, actualValue);
    }

    /** Прикладывает снимок фактического выходного значения. */
    public static void output(String name, Object actualValue) {
        attach("Output: " + name, actualValue);
    }

    /** Прикладывает тип, сообщение и доступный HTTP-статус фактической ошибки. */
    public static void exception(Throwable actual) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", actual.getClass().getName());
        details.put("message", actual.getMessage());
        if (actual instanceof ResponseStatusException statusException) {
            details.put("httpStatus", statusException.getStatusCode().value());
        }
        attach("Actual exception", details);
    }

    /** Выполняет операцию и прикладывает сведения об ошибке перед её повторным выбросом. */
    public static <T> T capture(ThrowingSupplier<T> operation) throws Throwable {
        try {
            return operation.get();
        } catch (Throwable failure) {
            exception(failure);
            throw failure;
        }
    }

    /** Преобразует значение в безопасный JSON-снимок и прикладывает его к Allure. */
    private static void attach(String name, Object value) {
        Allure.addAttachment(name, "application/json", jsonEvidence(snapshot(value)));
    }

    /** Создаёт снимок значения без раскрытия полей с чувствительными именами. */
    private static Object snapshot(Object value) {
        return snapshot(value, 0);
    }

    /** Ограниченно обходит вложенное значение и заменяет неподдерживаемые объекты описанием. */
    private static Object snapshot(Object value, int depth) {
        if (value instanceof String text) return HttpEvidence.redact(text);
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof UUID
                || value instanceof TemporalAccessor
                || value instanceof TemporalAmount
                || value instanceof URI
                || value instanceof Path
                || value instanceof ZoneId
                || value instanceof java.util.Date) {
            return HttpEvidence.redact(value.toString());
        }
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (depth >= 8)
            return Map.of("type", value.getClass().getName(), "snapshot", "nested value omitted");
        if (value instanceof byte[] bytes) {
            return Map.of("type", "byte[]", "length", bytes.length, "sha256", sha256(bytes));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach(
                    (key, item) -> {
                        String name = String.valueOf(key);
                        result.put(
                                name,
                                isSensitiveField(name) ? "[REDACTED]" : snapshot(item, depth + 1));
                    });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(snapshot(item, depth + 1)));
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(snapshot(Array.get(value, index), depth + 1));
            }
            return result;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    Method accessor = component.getAccessor();
                    if (!accessor.canAccess(value)) accessor.trySetAccessible();
                    String name = component.getName();
                    result.put(
                            name,
                            isSensitiveField(name)
                                    ? "[REDACTED]"
                                    : snapshot(accessor.invoke(value), depth + 1));
                } catch (ReflectiveOperationException failure) {
                    result.put(component.getName(), "<unavailable>");
                }
            }
            return result;
        }
        return Map.of(
                "type",
                value.getClass().getName(),
                "snapshot",
                "unsupported object; pass an explicit Map");
    }

    /** Сериализует подготовленный снимок; при ошибке возвращает краткую диагностическую запись. */
    private static String jsonEvidence(Object snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            return "{\"serializationError\":\"" + failure.getClass().getSimpleName() + "\"}";
        }
    }

    /** Определяет чувствительное имя поля без учёта регистра. */
    private static boolean isSensitiveField(String name) {
        return SENSITIVE_FIELDS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Вычисляет SHA-256 для байтового содержимого. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Содержит имя и фактическое значение входного параметра шага. */
    public record Input(String name, Object value) {}

    /** Описывает операцию, возвращающую значение и допускающую любое исключение. */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        /** Выполняет операцию и возвращает её результат. */
        T get() throws Throwable;
    }

    /** Описывает операцию без результата, допускающую любое исключение. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        /** Выполняет операцию. */
        void run() throws Throwable;
    }

    /** Описывает проверку результата, допускающую любое исключение. */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        /** Проверяет переданное значение. */
        void accept(T value) throws Throwable;
    }
}
