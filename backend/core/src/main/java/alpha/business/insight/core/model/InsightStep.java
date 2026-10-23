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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Шаг анализа, сохранённый для трассировки разбора.
 */
@Entity
@Table(name = "insight_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightStep {
    /**
     * Идентификатор шага анализа.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Разбор, в рамках которого выполнен шаг.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insight_id", nullable = false)
    private Insight insight;

    /**
     * Порядковый номер шага внутри разбора, начиная с нуля.
     */
    @Column(name = "step_no", nullable = false)
    private Integer stepNo;

    /**
     * Вид шага анализа или вызванного инструмента.
     */
    @Column(name = "kind", nullable = false, columnDefinition = "text")
    private String kind;

    /**
     * Входные данные и результат выполнения шага.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    /**
     * Время сохранения шага анализа.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
