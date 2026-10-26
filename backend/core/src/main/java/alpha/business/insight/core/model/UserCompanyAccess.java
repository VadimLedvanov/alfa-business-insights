package alpha.business.insight.core.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Право пользователя работать с данными компании.
 */
@Entity
@Table(name = "user_company_access")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCompanyAccess {
    /**
     * Пара идентификаторов пользователя и доступной ему компании.
     */
    @EmbeddedId
    private UserCompanyAccessId id;

    /**
     * Компания, к данным которой предоставлен доступ.
     */
    @MapsId("companyId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;
}
