package alpha.business.insight.core.repository;

import alpha.business.insight.core.dto.EventListItem;
import alpha.business.insight.core.model.Event;
import alpha.business.insight.core.model.EventSourceType;
import alpha.business.insight.core.model.EventStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JPA-хранилище событий и их исходных материалов.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    Page<Event> findByStatusAndSourceType(EventStatus status, EventSourceType sourceType, Pageable pageable);

    @Query("""
            SELECT new alpha.business.insight.core.dto.EventListItem(
                e.id, e.title, e.sourceType, e.status, e.createdAt)
            FROM Event e
            WHERE (:title IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', CAST(:title AS String), '%')) ESCAPE '!')
              AND (:status IS NULL OR e.status = :status)
              AND (:sourceType IS NULL OR e.sourceType = :sourceType)
            """)
    Page<EventListItem> findEvents(@Param("title") String title,
                                 @Param("status") EventStatus status,
                                 @Param("sourceType") EventSourceType sourceType,
                                 Pageable pageable);

    /** Сериализует генерации события, чтобы параллельные POST не дублировали платные вызовы. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findForInsight(@Param("id") UUID id);
}
