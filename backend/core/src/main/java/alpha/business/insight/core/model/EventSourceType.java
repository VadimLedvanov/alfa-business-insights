package alpha.business.insight.core.model;

/**
 * Тип источника события.
 */
public enum EventSourceType {
    /**
     * Текст, введённый пользователем.
     */
    TEXT,
    /**
     * Ссылка на исходную публикацию.
     */
    URL,
    /**
     * Загруженный документ PDF.
     */
    PDF
}
