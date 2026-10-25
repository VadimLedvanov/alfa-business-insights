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
 * Сообщение диалога в контексте персонального разбора.
 */
@Entity
@Table(name = "dialog_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DialogMessage {
    /**
     * Идентификатор сообщения диалога.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Разбор, в контексте которого ведётся диалог.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "insight_id", nullable = false)
    private Insight insight;

    /**
     * Роль автора сообщения в диалоге.
     */
    @Column(name = "role", nullable = false, columnDefinition = "text")
    private String role;

    /**
     * Текст сообщения пользователя или ответа системы.
     */
    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /**
     * Время сохранения сообщения.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static DialogMessage of(Insight insight, String role, String text) {
        DialogMessage message = new DialogMessage();
        message.insight = insight;
        message.role = role;
        message.text = text;
        message.createdAt = Instant.now();
        return message;
    }
}
