package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JPA-хранилище событий и их исходных материалов.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    /** Сериализует генерации события, чтобы параллельные POST не дублировали платные вызовы. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findForInsight(@Param("id") UUID id);
}
