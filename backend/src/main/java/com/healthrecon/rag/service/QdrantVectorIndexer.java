package com.healthrecon.rag.service;

import com.google.common.util.concurrent.ListenableFuture;
import com.healthrecon.rag.config.LlmProperties;
import com.healthrecon.rag.config.QdrantProperties;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.service.chunking.TextChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Vector storage backed by Qdrant over gRPC. Each document set gets its own
 * collection (named after the set UUID) so search is scoped without extra
 * filters. Metadata keys are stored as plain strings to survive the
 * LangChain4j payload round-trip.
 */
@Component
public class QdrantVectorIndexer implements VectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorIndexer.class);
    private static final Duration OP_TIMEOUT = Duration.ofSeconds(30);

    private static final String META_DOC_ID = "doc_id";
    private static final String META_FILENAME = "filename";
    private static final String META_SECTION = "section";

    private final QdrantProperties properties;
    private final LlmProperties llmProperties;
    private volatile QdrantClient client;

    public QdrantVectorIndexer(QdrantProperties properties, LlmProperties llmProperties) {
        this.properties = properties;
        this.llmProperties = llmProperties;
    }

    @Override
    public void ensureCollection(UUID docSetId) {
        Collections.VectorParams params = Collections.VectorParams.newBuilder()
                .setSize(llmProperties.embeddingDimension())
                .setDistance(Collections.Distance.Cosine)
                .build();
        bestEffort(() -> client().createCollectionAsync(collectionName(docSetId), params), "ensure collection");
    }

    @Override
    public void upsert(StoredDocument doc, List<TextChunk> chunks, List<TextSegment> segments, List<Embedding> embeddings) {
        ensureCollection(doc.getDocSetId());
        QdrantEmbeddingStore store = storeFor(doc.getDocSetId());
        store.removeAll(docFilter(doc.getId()));
        if (chunks.isEmpty()) {
            return;
        }
        List<String> ids = chunks.stream()
                .map(chunk -> deterministicId(doc.getId(), chunk.chunkIndex()))
                .toList();
        store.addAll(ids, embeddings, segments);
    }

    @Override
    public void deleteDocument(UUID docSetId, UUID docId) {
        deleteVoid(() -> storeFor(docSetId).removeAll(docFilter(docId)), "delete document vectors");
    }

    @Override
    public void deleteSet(UUID docSetId) {
        bestEffort(() -> client().deleteCollectionAsync(collectionName(docSetId)), "delete collection");
    }

    @Override
    public List<ChunkSearchHit> search(UUID docSetId, Embedding queryEmbedding, int topK) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = storeFor(docSetId).search(request).matches();
        return matches.stream()
                .filter(match -> match.embedded() != null)
                .map(this::toHit)
                .toList();
    }

    private ChunkSearchHit toHit(EmbeddingMatch<TextSegment> match) {
        Metadata metadata = match.embedded().metadata();
        UUID docId = safeUuid(metadata.getString(META_DOC_ID));
        return new ChunkSearchHit(
                docId,
                metadata.getString(META_FILENAME),
                metadata.getString(META_SECTION),
                match.score(),
                match.embedded().text());
    }

    static String deterministicId(UUID docId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((docId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static Filter docFilter(UUID docId) {
        return MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(docId.toString());
    }

    static TextSegment segmentFor(StoredDocument doc, TextChunk chunk) {
        Metadata metadata = new Metadata()
                .put(META_DOC_ID, doc.getId().toString())
                .put(META_FILENAME, doc.getFilename())
                .put(META_SECTION, chunk.section())
                .put("chunk_index", chunk.chunkIndex());
        return new TextSegment(chunk.text(), metadata);
    }

    private QdrantEmbeddingStore storeFor(UUID docSetId) {
        return new QdrantEmbeddingStore(client(), collectionName(docSetId), "text_segment");
    }

    private QdrantClient client() {
        QdrantClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                            properties.host(), properties.grpcPort(), false);
                    if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                        builder.withApiKey(properties.apiKey());
                    }
                    local = new QdrantClient(builder.build());
                    client = local;
                }
            }
        }
        return local;
    }

    private static String collectionName(UUID docSetId) {
        return docSetId.toString();
    }

    private static UUID safeUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void bestEffort(Supplier<ListenableFuture<?>> operation, String description) {
        try {
            operation.get().get(OP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Best-effort qdrant operation failed ({}): {}", description, e.getMessage());
        }
    }

    private static void deleteVoid(VoidOperation operation, String description) {
        try {
            operation.run();
        } catch (Exception e) {
            log.debug("Best-effort qdrant operation failed ({}): {}", description, e.getMessage());
        }
    }

    @FunctionalInterface
    interface VoidOperation {
        void run() throws Exception;
    }
}