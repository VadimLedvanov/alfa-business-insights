package alpha.business.insight.core.dto;

import alpha.business.insight.core.model.DialogMessage;
import java.time.Instant;
import java.util.UUID;

public record DialogMessageDto(UUID id, String role, String text, Instant createdAt) {
    public static DialogMessageDto from(DialogMessage message) {
        return new DialogMessageDto(message.getId(), message.getRole(), message.getText(), message.getCreatedAt());
    }
}
