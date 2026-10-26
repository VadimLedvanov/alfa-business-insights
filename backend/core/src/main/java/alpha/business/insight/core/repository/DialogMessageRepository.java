package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.DialogMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DialogMessageRepository extends JpaRepository<DialogMessage, UUID> {
    List<DialogMessage> findByInsightIdOrderByCreatedAtAscIdAsc(UUID insightId);
}
