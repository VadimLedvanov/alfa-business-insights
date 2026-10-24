package alpha.business.insight.core.qa.support;

import alpha.business.insight.core.qa.helpers.TestEvidence;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/** Собирает ограниченные снимки состояния известных тестовых фикстур без их изменения. */
final class LegacyTestEvidence {
    private static final Set<String> CSV_TABLES =
            Set.of(
                    "companies",
                    "person_company_links",
                    "company_payment_summaries",
                    "databasechangelog");
    private static final Set<String> COMPANY_TABLES =
            Set.of("companies", "events", "user_company_access", "insights");

    /** Запрещает создавать экземпляры утилиты для снимков фикстур. */
    private LegacyTestEvidence() {}

    /** Прикладывает снимки базы и файловых фикстур до или после вызова теста. */
    static void attach(
            ExtensionContext context,
            ReflectiveInvocationContext<Method> invocationContext,
            boolean before) {
        String phase = before ? "до" : "после";
        try {
            List<Object> instances = context.getRequiredTestInstances().getAllInstances();
            JdbcTemplate jdbc = jdbcTemplate(instances);
            if (jdbc != null) {
                capture(
                        "PostgreSQL " + phase,
                        before,
                        () -> databaseSnapshot(jdbc, context.getRequiredTestClass(), instances));
            }
            for (Path root : fixtureRoots(context, instances, invocationContext)) {
                capture("Файлы тестовой фикстуры " + phase, before, () -> storageSnapshot(root));
            }
        } catch (Throwable failure) {
            attachFailure("Снимки фикстур " + phase, failure, before);
        }
    }

    /** Снимает состояние фикстуры и прикладывает его как вход или фактический результат. */
    private static void capture(String name, boolean input, SnapshotSupplier supplier) {
        try {
            Map<String, Object> snapshot = supplier.get();
            if (input) TestEvidence.input(name, snapshot);
            else TestEvidence.output(name, snapshot);
        } catch (Throwable failure) {
            attachFailure(name, failure, input);
        }
    }

    /** Прикладывает сведения о сбое сбора снимка, включая SQLSTATE при наличии. */
    private static void attachFailure(String name, Throwable failure, boolean input) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capture", "failed");
        details.put("type", failure.getClass().getName());
        details.put("message", failure.getMessage());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                details.put("sqlState", sqlException.getSQLState());
                break;
            }
        }
        if (input) TestEvidence.input("Снимок не получен: " + name, details);
        else TestEvidence.output("Снимок не получен: " + name, details);
    }

    /** Находит JdbcTemplate в полях тестовых экземпляров и родительских классов. */
    private static JdbcTemplate jdbcTemplate(List<Object> instances) {
        for (Object instance : instances) {
            for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getName().equals("jdbc")
                            && JdbcTemplate.class.isAssignableFrom(field.getType())) {
                        try {
                            field.setAccessible(true);
                            Object value =
                                    field.get(
                                            Modifier.isStatic(field.getModifiers())
                                                    ? null
                                                    : instance);
                            if (value instanceof JdbcTemplate jdbc) return jdbc;
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(
                                    "Не удалось прочитать fixture JdbcTemplate", failure);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Сохраняет ограниченные строки выбранных таблиц и комментарии схемы при необходимости. */
    private static Map<String, Object> databaseSnapshot(
            JdbcTemplate jdbc, Class<?> testClass, List<Object> instances) {
        List<String> availableTables =
                jdbc.queryForList(
                        """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                ORDER BY table_name LIMIT 51
                """,
                        String.class);
        boolean tableListTruncated = availableTables.size() > 50;
        if (tableListTruncated) availableTables = availableTables.subList(0, 50);
        Set<String> selectedTables = selectedTables(testClass, instances, availableTables);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String table : availableTables) {
            if (!selectedTables.contains(table)) continue;
            String identifier = "\"" + table.replace("\"", "\"\"") + "\"";
            Long rowCount = jdbc.queryForObject("SELECT count(*) FROM " + identifier, Long.class);
            List<String> rows =
                    jdbc.queryForList(
                            "SELECT to_jsonb(t)::text FROM "
                                    + identifier
                                    + " t ORDER BY t.ctid LIMIT 20",
                            String.class);
            snapshot.put(
                    table,
                    Map.of(
                            "rowCount",
                            rowCount,
                            "sampleRows",
                            rows,
                            "truncated",
                            rowCount > rows.size()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tables", snapshot);
        result.put("tableListTruncated", tableListTruncated);
        if (testClass.getName().contains("Schema")
                || instances.stream()
                        .map(instance -> instance.getClass().getName())
                        .anyMatch(name -> name.contains("Schema"))) {
            result.put(
                    "tableComments",
                    jdbc.queryForList(
                            """
                    SELECT c.relname AS table_name, obj_description(c.oid, 'pg_class') AS comment
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema() AND c.relkind = 'r'
                    ORDER BY c.relname LIMIT 50
                    """));
            result.put(
                    "columnComments",
                    jdbc.queryForList(
                            """
                    SELECT c.relname AS table_name, a.attname AS column_name,
                        col_description(c.oid, a.attnum) AS comment
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid
                    WHERE n.nspname = current_schema() AND c.relkind = 'r'
                        AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY c.relname, a.attnum LIMIT 250
                    """));
        }
        return result;
    }

    /** Выбирает таблицы снимка по функциональной области тестового класса. */
    private static Set<String> selectedTables(
            Class<?> testClass, List<Object> instances, List<String> availableTables) {
        StringBuilder testNames = new StringBuilder(testClass.getName());
        instances.stream()
                .map(instance -> instance.getClass().getName())
                .forEach(name -> testNames.append('|').append(name));
        String name = testNames.toString();
        if (name.contains("Csv") || name.contains("DataImport")) {
            return intersection(availableTables, CSV_TABLES);
        }
        if (name.contains("Company")) return intersection(availableTables, COMPANY_TABLES);
        if (name.contains("Document")) return intersection(availableTables, Set.of("documents"));
        if (name.contains("Schema")) {
            return availableTables.stream()
                    .filter(table -> !table.startsWith("databasechangelog"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    /** Оставляет таблицы, присутствующие в базе и относящиеся к снимку. */
    private static Set<String> intersection(List<String> available, Set<String> relevant) {
        return available.stream()
                .filter(relevant::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Собирает каталоги из @TempDir и известной файловой фикстуры документов. */
    private static List<Path> fixtureRoots(
            ExtensionContext context,
            List<Object> instances,
            ReflectiveInvocationContext<Method> invocationContext) {
        List<Path> roots = new ArrayList<>();
        for (Object instance : instances) {
            for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.isAnnotationPresent(TempDir.class)) continue;
                    try {
                        field.setAccessible(true);
                        addPath(
                                roots,
                                field.get(
                                        Modifier.isStatic(field.getModifiers()) ? null : instance));
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(
                                "Не удалось прочитать @TempDir fixture", failure);
                    }
                }
            }
        }
        var parameters = invocationContext.getExecutable().getParameters();
        List<Object> arguments = invocationContext.getArguments();
        for (int index = 0; index < Math.min(parameters.length, arguments.size()); index++) {
            if (parameters[index].isAnnotationPresent(TempDir.class))
                addPath(roots, arguments.get(index));
        }
        addKnownDocumentStorage(roots, context.getRequiredTestClass());
        return roots.stream().filter(Files::isDirectory).distinct().toList();
    }

    /** Добавляет корень хранилища для интеграционного теста загрузки документов. */
    private static void addKnownDocumentStorage(List<Path> roots, Class<?> testClass) {
        if (!testClass.getSimpleName().equals("DocumentUploadIntegrationTest")) return;
        try {
            Field field = testClass.getDeclaredField("STORAGE");
            field.setAccessible(true);
            addPath(roots, field.get(null));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Не удалось прочитать document STORAGE fixture", failure);
        }
    }

    /** Добавляет путь из значения Path или File в список корней фикстур. */
    private static void addPath(List<Path> roots, Object value) {
        if (value instanceof Path path) roots.add(path);
        if (value instanceof File file) roots.add(file.toPath());
    }

    /** Собирает сведения о файлах и текст малых CSV, TXT и JSON-фикстур. */
    private static Map<String, Object> storageSnapshot(Path root) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).limit(100).toList()) {
                long size = Files.size(file);
                Map<String, Object> actualFile = new LinkedHashMap<>();
                actualFile.put("path", root.relativize(file).toString());
                actualFile.put("sizeBytes", size);
                actualFile.put("sha256", sha256(file));
                if (isSmallTextFixture(file, size)) {
                    actualFile.put("text", Files.readString(file, StandardCharsets.UTF_8));
                }
                files.add(actualFile);
            }
        }
        return Map.of(
                "root",
                root.getFileName().toString(),
                "fileCountCaptured",
                files.size(),
                "files",
                files);
    }

    /** Определяет малые текстовые фикстуры, допустимые для вложения в отчёт. */
    private static boolean isSmallTextFixture(Path file, long size) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return size <= 8192
                && (name.endsWith(".csv") || name.endsWith(".txt") || name.endsWith(".json"));
    }

    /** Вычисляет SHA-256 файла потоковым чтением. */
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Описывает операцию получения снимка базы или файловой фикстуры. */
    @FunctionalInterface
    private interface SnapshotSupplier {
        /** Возвращает снимок и допускает ошибки чтения источника. */
        Map<String, Object> get() throws Exception;
    }
}
