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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Сохранённый разбор события для компании с версией материалов.
 */
@Entity
@Table(name = "insights")
@Getter
@Setter
@NoArgsConstructor
public class Insight {
    /**
     * Идентификатор персонального разбора.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Компания, для которой подготовлен разбор.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /**
     * Событие, влияние которого анализируется.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /**
     * Версия материалов, использованная при подготовке разбора.
     */
    @Column(name = "materials_version", nullable = false)
    private Long materialsVersion;

    /**
     * Результат анализа с фактами, выводами и источниками.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /**
     * Стоимость подготовки разбора в рублях.
     */
    @Column(name = "cost_rub", nullable = false, columnDefinition = "numeric")
    private BigDecimal costRub;

    /**
     * Время сохранения разбора.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
