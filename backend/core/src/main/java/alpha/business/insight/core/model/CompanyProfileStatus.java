package alpha.business.insight.core.model;

/**
 * Статус профиля компании.
 */
public enum CompanyProfileStatus {
    /**
     * Профиль загружен.
     */
    AVAILABLE,
    /**
     * Профиль отсутствует.
     */
    MISSING,
    /**
     * Профиль содержит противоречивые данные.
     */
    CONFLICTING
}
