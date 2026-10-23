package alpha.business.insight.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие, загруженное для анализа.
 */
@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {
    /**
     * Идентификатор события.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Заголовок события.
     */
    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    /**
     * Тип исходного материала: текст, ссылка или PDF.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, columnDefinition = "text")
    private EventSourceType sourceType;

    /**
     * Исходный текст, URL или ссылка на сохранённый PDF.
     */
    @Column(name = "source_ref", nullable = false, columnDefinition = "text")
    private String sourceRef;

    /**
     * Текущее состояние обработки события.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "text")
    private EventStatus status;

    /**
     * Время создания события.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Версия материалов для проверки актуальности инсайтов.
     */
    @Column(name = "materials_version", nullable = false)
    private Long materialsVersion;
}
