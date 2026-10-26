package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Составной идентификатор доступа пользователя к компании.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCompanyAccessId implements Serializable {
    /**
     * Идентификатор пользователя из claim sub в Keycloak.
     */
    @Column(name = "user_id", nullable = false, columnDefinition = "text")
    private String userId;

    /**
     * Обезличенный идентификатор доступной компании.
     */
    @Column(name = "company_id", nullable = false, columnDefinition = "text")
    private String companyId;

    public UserCompanyAccessId(String userId, String companyId) {
        this.userId = userId;
        this.companyId = companyId;
    }
}
