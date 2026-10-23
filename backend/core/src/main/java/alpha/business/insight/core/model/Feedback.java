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

import java.time.Instant;
import java.util.UUID;

/**
 * Оценка пользователем персонального разбора.
 */
@Entity
@Table(name = "feedback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feedback {
    /**
     * Идентификатор пользовательской оценки.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Разбор, которому выставлена оценка.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insight_id", nullable = false)
    private Insight insight;

    /**
     * Идентификатор пользователя из claim sub в Keycloak.
     */
    @Column(name = "user_id", nullable = false, columnDefinition = "text")
    private String userId;

    /**
     * Оценка полезности: 1 — полезно, -1 — неполезно.
     */
    @Column(name = "value", nullable = false)
    private Short value;

    /**
     * Время сохранения оценки.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
