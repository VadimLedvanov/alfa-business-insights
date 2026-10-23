package alpha.business.insight.core.repository;

import alpha.business.insight.core.model.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * JPA-хранилище метаданных PDF. Чанки и их тексты находятся только в Qdrant.
 */
@Repository
public interface DocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {}
