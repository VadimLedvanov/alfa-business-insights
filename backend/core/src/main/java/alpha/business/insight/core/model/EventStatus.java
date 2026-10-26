package alpha.business.insight.core.model;

/**
 * Состояние обработки события.
 */
public enum EventStatus {
    /**
     * Материалы обрабатываются.
     */
    PROCESSING,
    /**
     * Материалы готовы к анализу.
     */
    READY,
    /**
     * Обработка завершилась ошибкой.
     */
    FAILED
}
