package alpha.business.insight.core.qa.documents;

import static org.assertj.core.api.Assertions.assertThat;

import alpha.business.insight.core.service.DocumentVectorStore;
import org.junit.jupiter.api.Test;

/** Проверяет, что каждый embedder и размерность хранятся в своей коллекции Qdrant. */
class DocumentVectorStoreCollectionTest {
    @Test
    void multilingualE5UsesItsOwn384DimensionCollection() {
        assertThat(DocumentVectorStore.collectionName("intfloat/multilingual-e5-small", 384, false))
                .isEqualTo("knowledge_documents_intfloat_multilingual_e5_small_384");
    }

    @Test
    void stubCollectionNameRemainsStableForExistingDocuments() {
        assertThat(DocumentVectorStore.collectionName("stub-v1", 8, true))
                .isEqualTo(DocumentVectorStore.STUB_COLLECTION);
    }
}
