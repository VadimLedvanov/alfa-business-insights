package alpha.business.insight.core.dto;

import java.util.List;

/**
 * Отчёт об обработке файла CSV.
 */
public record FileReport(
        /**
         * Имя файла.
         */
        String file,
        /**
         * Количество прочитанных записей.
         */
        long read,
        /**
         * Количество принятых записей, включая повторы.
         */
        long accepted,
        /**
         * Количество отклонённых записей.
         */
        long rejected,
        /**
         * Количество принятых записей с повторяющимся ключом сверх первой записи.
         */
        long repeatedKeys,
        /**
         * Примеры ошибок обработки записей.
         */
        List<RowError> errorSamples) { }
