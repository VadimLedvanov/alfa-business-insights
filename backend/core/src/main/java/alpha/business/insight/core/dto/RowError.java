package alpha.business.insight.core.dto;

/**
 * Ошибка обработки записи CSV.
 */
public record RowError(
        /**
         * Номер записи CSV без учёта заголовка.
         */
        long recordNumber,
        /**
         * Причина отклонения записи.
         */
        String reason) { }
