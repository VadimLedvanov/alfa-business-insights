package alpha.business.insight.core.qa.support;

import alpha.business.insight.core.qa.helpers.TestEvidence;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Группирует результаты Allure и описывает фактически выполненные шаги тестов. */
public final class TestPlanExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback,
        InvocationInterceptor {
    private static final Properties PLANS = loadProperties("/test-plans.properties");
    private static final Properties NAMES = loadProperties("/test-names.properties");
    private static final Pattern STEP_NUMBER = Pattern.compile("^\\s*\\d+(?:\\.\\d+)*\\.\\s*");

    /** Устанавливает исходное описание проверки и её каноническое имя в Allure. */
    @Override
    public void beforeTestExecution(ExtensionContext context) {
        String className = context.getRequiredTestClass().getName();
        String suite = className.contains("Insight") || className.contains("LlmClient") ? "Инсайты"
                : className.contains("Event") ? "События"
                : className.contains("ApiExceptionHandler") ? "Ошибки API"
                : className.contains("HttpEvidence") ? "HTTP-диагностика"
                : className.contains("Company") ? "Контекст компании"
                : className.contains("Csv") ? "Импорт CSV"
                : className.contains("Document") ? "Документы"
                : className.contains("Security") || className.contains("Authorization") ? "Безопасность"
                : "Схема данных";
        String layer = context.getTags().contains("smoke") ? "Smoke"
                : context.getTags().contains("unit") ? "Unit"
                : context.getTags().contains("e2e") ? "E2E" : "Integration";
        String key = key(context);
        String caseName = NAMES.getProperty(key);
        if (caseName == null) throw new IllegalStateException("Missing test name for " + key);
        String invocationName = context.getUniqueId().contains("[test-template-invocation:")
                ? context.getDisplayName() : "";
        String reportName = invocationName.isEmpty() ? caseName
                : invocationName.startsWith(caseName) ? invocationName : caseName + " " + invocationName;
        Allure.getLifecycle().getCurrentTestCase().ifPresent(uuid ->
                Allure.getLifecycle().updateTestCase(uuid, result -> {
                    Set<String> suiteLabelNames = Set.of("parentSuite", "suite", "subSuite");
                    result.getLabels().removeIf(label -> suiteLabelNames.contains(label.getName()));
                    result.getLabels().add(new Label().setName("parentSuite").setValue("Backend"));
                    result.getLabels().add(new Label().setName("suite").setValue(layer));
                    result.getLabels().add(new Label().setName("subSuite").setValue(suite));
                    result.setName(reportName);
                    result.setDescription(renderInitialDescription(PLANS.getProperty(key), key));
                }));
    }

    /** Обновляет отчёт списком фактически выполненных шагов проверки. */
    @Override
    public void afterTestExecution(ExtensionContext context) {
        String key = key(context);
        String planText = PLANS.getProperty(key);
        TestPlan plan = parsePlan(planText);
        AtomicReference<String> description = new AtomicReference<>();
        Allure.getLifecycle().getCurrentTestCase().ifPresent(uuid ->
                Allure.getLifecycle().updateTestCase(uuid, result -> {
                    numberSteps(result.getSteps(), "");
                    String rendered = renderExecutedDescription(plan, key, result);
                    result.setDescription(rendered);
                    description.set(rendered);
                }));
        if (description.get() != null) {
            Allure.addAttachment("План проверки", "text/plain", description.get());
        }
    }

    /** Оборачивает обычный тестовый метод в шаг Allure с фактическими данными. */
    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        invokeWithLegacyStep(invocation, invocationContext, extensionContext);
    }

    /** Оборачивает шаблонный тестовый метод в шаг Allure с фактическими данными. */
    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        invokeWithLegacyStep(invocation, invocationContext, extensionContext);
    }

    /** Исполняет метод и собирает диагностические снимки для тестов с планом. */
    private static void invokeWithLegacyStep(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext context) throws Throwable {
        if (context.getRequiredTestClass().getPackageName().contains(".qa.")) {
            invocation.proceed();
            return;
        }
        TestPlan plan = parsePlan(PLANS.getProperty(key(context)));
        if (plan == null || plan.steps().isEmpty()) {
            invocation.proceed();
            return;
        }
        Allure.step(plan.title(), () -> {
            Map<String, Object> invocationDetails = new LinkedHashMap<>();
            invocationDetails.put("class", context.getRequiredTestClass().getName());
            invocationDetails.put("method", context.getRequiredTestMethod().getName());
            invocationDetails.put("arguments", invocationContext.getArguments());
            TestEvidence.input("JUnit-сценарий", invocationDetails);
            LegacyTestEvidence.attach(context, invocationContext, true);

            Throwable invocationFailure = null;
            try {
                invocation.proceed();
                TestEvidence.output("JUnit invocation/assertion outcome",
                        Map.of("methodOutcome", "returned normally",
                                "serviceResult", "not captured by JUnit invocation interceptor"));
            } catch (Throwable failure) {
                invocationFailure = failure;
                TestEvidence.exception(failure);
            }

            try {
                LegacyTestEvidence.attach(context, invocationContext, false);
            } catch (Throwable evidenceFailure) {
                TestEvidence.output("Ошибка сбора диагностического снимка", Map.of(
                        "type", evidenceFailure.getClass().getName(),
                        "message", String.valueOf(evidenceFailure.getMessage())));
            }
            if (invocationFailure != null) throw invocationFailure;
        });
    }

    /** Нумерует шаги отчёта и их дочерние шаги в порядке выполнения. */
    private static void numberSteps(List<StepResult> steps, String parentNumber) {
        if (steps == null) return;
        for (int index = 0; index < steps.size(); index++) {
            StepResult step = steps.get(index);
            String number = parentNumber.isEmpty() ? Integer.toString(index + 1)
                    : parentNumber + "." + (index + 1);
            String name = step.getName() == null ? "Шаг" : step.getName();
            step.setName(number + ". " + STEP_NUMBER.matcher(name).replaceFirst(""));
            numberSteps(step.getSteps(), number);
        }
    }

    /** Формирует исходное описание Allure из названия, плана и ожидаемого результата. */
    private static String renderInitialDescription(String text, String key) {
        TestPlan plan = parsePlan(text);
        if (plan == null) return missingPlan(key);
        StringBuilder rendered = new StringBuilder(plan.title()).append("\n\nШаги проверки:\n");
        for (int index = 0; index < plan.steps().size(); index++) {
            rendered.append(index + 1).append(". ").append(plan.steps().get(index)).append('\n');
        }
        return rendered.append("\nОжидаемый результат: ").append(plan.expected()).toString();
    }

    /** Формирует описание с деревом реально зарегистрированных шагов проверки. */
    private static String renderExecutedDescription(TestPlan plan, String key, TestResult result) {
        if (plan == null) return missingPlan(key);
        StringBuilder rendered = new StringBuilder(plan.title()).append("\n\nФактически исполненные шаги:\n");
        StringBuilder executedTree = new StringBuilder();
        appendStepTree(result.getSteps(), 0, executedTree);
        if (executedTree.isEmpty()) {
            rendered.append("(шаги не зарегистрированы)\n");
        } else {
            rendered.append(executedTree);
        }
        return rendered.append("\nОжидаемый результат: ").append(plan.expected()).toString();
    }

    /** Добавляет шаги и вложенные шаги в форматированное дерево описания. */
    private static void appendStepTree(List<StepResult> steps, int depth, StringBuilder rendered) {
        if (steps == null) return;
        for (StepResult step : steps) {
            rendered.append("    ".repeat(depth));
            if (depth > 0) rendered.append("- ");
            rendered.append(step.getName()).append('\n');
            appendStepTree(step.getSteps(), depth + 1, rendered);
        }
    }

    /** Разбирает строку плана и отклоняет пустые или неполные секции. */
    private static TestPlan parsePlan(String text) {
        if (text == null) return null;
        String[] sections = text.split("\\|\\|", 2);
        if (sections.length != 2 || sections[0].isBlank() || sections[1].isBlank()) return null;
        String[] parts = sections[1].split("\\|", -1);
        if (parts.length < 2 || parts[parts.length - 1].isBlank()) return null;
        List<String> steps = new ArrayList<>();
        for (int index = 0; index < parts.length - 1; index++) {
            if (parts[index].isBlank()) return null;
            steps.add(parts[index].strip());
        }
        return new TestPlan(sections[0].strip(), steps, parts[parts.length - 1].strip());
    }

    /** Возвращает ключ метаданных Allure для класса и метода теста. */
    private static String key(ExtensionContext context) {
        return context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName();
    }

    /** Формирует сообщение об отсутствующем или некорректном плане проверки. */
    private static String missingPlan(String key) {
        return "План проверки не найден или имеет неверный формат для " + key
                + ". План не выведен из реализации; проверьте test-plans.properties.";
    }

    /** Загружает свойства метаданных в UTF-8 и завершает запуск при ошибке ресурса. */
    private static Properties loadProperties(String resource) {
        try (InputStream input = TestPlanExtension.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing test metadata resource " + resource);
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return properties;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Хранит заголовок плана, список шагов и ожидаемый результат проверки. */
    private record TestPlan(String title, List<String> steps, String expected) {
    }
}
