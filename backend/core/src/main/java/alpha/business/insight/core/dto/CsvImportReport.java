package alpha.business.insight.core.dto;

import java.util.List;

/**
 * Отчёт об импорте CSV.
 */
public record CsvImportReport(
        /**
         * Отчёты об обработке файлов.
         */
        List<FileReport> files,
        /**
         * Количество компаний с конфликтующими профилями в текущем импорте.
         */
        long conflictingCompanyProfiles,
        /**
         * Примеры идентификаторов компаний с конфликтующими профилями.
         */
        List<String> conflictingCompanyIdSamples,
        /**
         * Количество компаний без профиля в БД после импорта.
         */
        long companiesWithoutProfile) { }
