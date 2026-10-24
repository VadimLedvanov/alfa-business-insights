package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * JPA-хранилище событий и их исходных материалов.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
}
