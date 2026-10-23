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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Факт, извлечённый из фрагмента материала.
 */
@Entity
@Table(name = "facts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Fact {
    /**
     * Идентификатор извлечённого факта.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Событие, к которому относится факт.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /**
     * Фрагмент того же события, подтверждающий факт.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fragment_id", nullable = false)
    private Fragment fragment;

    /**
     * Текст подтверждённого факта.
     */
    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /**
     * Темы, к которым относится факт.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "topics", nullable = false, columnDefinition = "text[]")
    private String[] topics;

    /**
     * Факторы влияния на бизнес.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "factors", nullable = false, columnDefinition = "text[]")
    private String[] factors;

    /**
     * Отрасли, затронутые фактом.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "industries", nullable = false, columnDefinition = "text[]")
    private String[] industries;

    /**
     * Регионы, затронутые фактом.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "regions", nullable = false, columnDefinition = "text[]")
    private String[] regions;
}
