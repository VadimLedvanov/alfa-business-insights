package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Связь физического лица с компанией.
 */
@Entity
@Table(name = "person_company_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonCompanyLink {
    /**
     * Идентификатор записи связи.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Обезличенный идентификатор физического лица.
     */
    @Column(name = "person_id", nullable = false, columnDefinition = "text")
    private String personId;

    /**
     * Компания, связанная с физическим лицом.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /**
     * Город из записи связи.
     */
    @Column(name = "city_name", columnDefinition = "text")
    private String cityName;

    /**
     * Региональный центр из записи связи.
     */
    @Column(name = "regional_center_name", columnDefinition = "text")
    private String regionalCenterName;
}
