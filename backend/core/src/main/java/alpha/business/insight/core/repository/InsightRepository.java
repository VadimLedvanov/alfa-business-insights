package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.Insight;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface InsightRepository extends JpaRepository<Insight, UUID> {
    Optional<Insight> findByCompanyIdAndEventIdAndMaterialsVersion(String companyId, UUID eventId, Long materialsVersion);
}
