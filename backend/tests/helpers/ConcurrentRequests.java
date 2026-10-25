package alpha.business.insight.core.qa.helpers;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.util.ResultsUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Координирует небольшую группу параллельных HTTP-запросов с ограниченным ожиданием. */
public final class ConcurrentRequests<T> implements AutoCloseable {
    private final ExecutorService executor;
    private final CountDownLatch ready;
    private final CountDownLatch start;
    private final List<Future<T>> futures;
    private final String parentStepUuid;
    private final Duration timeout;

    private ConcurrentRequests(
            int requestCount, String parentStepUuid, Duration timeout, IntFunction<T> request) {
        this.executor = Executors.newFixedThreadPool(requestCount);
        this.ready = new CountDownLatch(requestCount);
        this.start = new CountDownLatch(1);
        this.futures = new ArrayList<>(requestCount);
        this.parentStepUuid = parentStepUuid;
        this.timeout = timeout;
        for (int index = 0; index < requestCount; index++) {
            int requestIndex = index;
            futures.add(
                    executor.submit(
                            () -> {
                                ready.countDown();
                                if (!start.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                                    throw new IllegalStateException(
                                            "Параллельный запрос не получил стартовый сигнал");
                                }
                                String stepUuid = java.util.UUID.randomUUID().toString();
                                Allure.getLifecycle()
                                        .startStep(
                                                parentStepUuid,
                                                stepUuid,
                                                new StepResult()
                                                        .setName(
                                                                "HTTP запрос "
                                                                        + (requestIndex + 1)));
                                try {
                                    T result =
                                            Allure.step(
                                                    "Отправить параллельный запрос",
                                                    () -> request.apply(requestIndex));
                                    Allure.getLifecycle()
                                            .updateStep(
                                                    stepUuid,
                                                    step -> step.setStatus(Status.PASSED));
                                    return result;
                                } catch (RuntimeException | Error failure) {
                                    Allure.getLifecycle()
                                            .updateStep(
                                                    stepUuid,
                                                    step ->
                                                            step.setStatus(
                                                                            ResultsUtils.getStatus(
                                                                                            failure)
                                                                                    .orElse(
                                                                                            Status
                                                                                                    .BROKEN))
                                                                    .setStatusDetails(
                                                                            new StatusDetails()
                                                                                    .setMessage(
                                                                                            String
                                                                                                    .valueOf(
                                                                                                            failure
                                                                                                                    .getMessage()))
                                                                                    .setTrace(
                                                                                            failure
                                                                                                    .toString())));
                                    throw failure;
                                } finally {
                                    Allure.getLifecycle().stopStep(stepUuid);
                                }
                            }));
        }
    }

    /** Подготавливает запросы, которые начнутся после общего стартового сигнала. */
    public static <T> ConcurrentRequests<T> startTogether(
            int requestCount, String parentStepUuid, Duration timeout, IntFunction<T> request) {
        return new ConcurrentRequests<>(requestCount, parentStepUuid, timeout, request);
    }

    /** Ждёт, пока каждый worker будет готов к общему старту. */
    public void awaitReady() throws InterruptedException {
        if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Параллельные запросы не подготовились вовремя");
        }
    }

    /** Одновременно отпускает подготовленные HTTP-запросы. */
    public void release() {
        start.countDown();
    }

    /** Собирает все ответы в пределах общего конечного тайм-аута. */
    public List<T> awaitResults() throws Exception {
        List<T> results = new ArrayList<>(futures.size());
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Future<T> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                throw new java.util.concurrent.TimeoutException(
                        "HTTP запросы не завершились вовремя");
            results.add(future.get(remaining, TimeUnit.NANOSECONDS));
        }
        return List.copyOf(results);
    }

    /** Останавливает worker-потоки даже при ошибке запроса или проверки. */
    @Override
    public void close() throws InterruptedException {
        start.countDown();
        executor.shutdownNow();
        if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                    "Параллельные HTTP worker-потоки не завершились вовремя");
        }
    }
}
