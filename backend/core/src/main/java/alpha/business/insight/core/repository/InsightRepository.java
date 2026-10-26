package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.Insight;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface InsightRepository extends JpaRepository<Insight, UUID> {
    Optional<Insight> findByCompanyIdAndEventIdAndMaterialsVersion(String companyId, UUID eventId, Long materialsVersion);
    boolean existsByCompanyIdAndEventIdAndMaterialsVersion(String companyId, UUID eventId, Long materialsVersion);

    @Query("select i.event.id from Insight i where i.company.id = :companyId and i.event.id in :eventIds "
            + "and i.materialsVersion = i.event.materialsVersion")
    List<UUID> findGeneratedEventIds(@Param("companyId") String companyId, @Param("eventIds") Collection<UUID> eventIds);
}
