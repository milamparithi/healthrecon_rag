package com.healthrecon.rag.service.semanticcache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.config.CacheProperties;
import com.healthrecon.rag.config.LlmProperties;
import com.healthrecon.rag.config.QdrantProperties;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Qdrant-backed semantic cache. A single shared collection holds the question
 * embeddings for every document set; entries are scoped with a {@code doc_set_id}
 * payload key so search can filter without extra collections. Points are keyed
 * by a deterministic id (doc set + question) so re-storing the same question
 * overwrites. All operations are best-effort: a cache failure never fails the chat.
 */
@Component
public class QdrantSemanticCache implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(QdrantSemanticCache.class);
    private static final Duration OP_TIMEOUT = Duration.ofSeconds(30);
    private static final String PAYLOAD_SELECTOR = "text_segment";

    private static final String META_DOC_SET = "doc_set_id";
    private static final String META_QUESTION = "question";
    private static final String META_ANSWER = "answer";
    private static final String META_SOURCES = "sources_json";
    private static final String META_CREATED_AT = "created_at";

    private final QdrantProperties qdrantProperties;
    private final LlmProperties llmProperties;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;
    private volatile QdrantClient client;
    private volatile boolean collectionCreated;

    public QdrantSemanticCache(QdrantProperties qdrantProperties,
                               LlmProperties llmProperties,
                               CacheProperties cacheProperties,
                               ObjectMapper objectMapper) {
        this.qdrantProperties = qdrantProperties;
        this.llmProperties = llmProperties;
        this.cacheProperties = cacheProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CachedAnswer> lookup(UUID docSetId, Embedding queryEmbedding) {
        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(1)
                    .filter(docSetFilter(docSetId))
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = storeFor().search(request).matches();
            if (matches.isEmpty() || matches.get(0).embedded() == null) {
                return Optional.empty();
            }
            EmbeddingMatch<TextSegment> best = matches.get(0);
            if (best.score() < cacheProperties.similarityThreshold()) {
                return Optional.empty();
            }
            if (isExpired(best.embedded().metadata()).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(toAnswer(best));
        } catch (Exception e) {
            log.debug("Semantic cache lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(UUID docSetId, Embedding queryEmbedding, String question, String answer,
                      List<SourceResponse> sources) {
        if (sources.isEmpty()) {
            return;
        }
        try {
            ensureCollection();
            String sourcesJson = objectMapper.writeValueAsString(sources);
            Metadata metadata = new Metadata()
                    .put(META_DOC_SET, docSetId.toString())
                    .put(META_QUESTION, question)
                    .put(META_ANSWER, answer)
                    .put(META_SOURCES, sourcesJson)
                    .put(META_CREATED_AT, System.currentTimeMillis());
            TextSegment segment = new TextSegment(question, metadata);
            String id = deterministicId(docSetId, question);
            storeFor().addAll(List.of(id), List.of(queryEmbedding), List.of(segment));
        } catch (Exception e) {
            log.debug("Semantic cache store failed: {}", e.getMessage());
        }
    }

    @Override
    public void invalidate(UUID docSetId) {
        deleteVoid(() -> storeFor().removeAll(docSetFilter(docSetId)), "invalidate semantic cache");
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void evictExpired() {
        if (!cacheProperties.enabled() || cacheProperties.ttlSeconds() <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - cacheProperties.ttlSeconds() * 1000;
        Filter filter = MetadataFilterBuilder.metadataKey(META_CREATED_AT).isLessThan(cutoff);
        deleteVoid(() -> storeFor().removeAll(filter), "evict expired semantic cache entries");
    }

    private CachedAnswer toAnswer(EmbeddingMatch<TextSegment> match) {
        Metadata metadata = match.embedded().metadata();
        List<SourceResponse> sources;
        try {
            sources = objectMapper.readValue(metadata.getString(META_SOURCES),
                    new TypeReference<List<SourceResponse>>() {
                    });
        } catch (Exception e) {
            log.debug("Semantic cache payload parse failed: {}", e.getMessage());
            sources = List.of();
        }
        return new CachedAnswer(metadata.getString(META_ANSWER), sources, match.score());
    }

    private Optional<Long> isExpired(Metadata metadata) {
        if (cacheProperties.ttlSeconds() <= 0) {
            return Optional.empty();
        }
        Long createdAt = metadata.getLong(META_CREATED_AT);
        if (createdAt == null) {
            return Optional.empty();
        }
        long expiresAt = createdAt + cacheProperties.ttlSeconds() * 1000;
        return expiresAt < System.currentTimeMillis() ? Optional.of(expiresAt) : Optional.empty();
    }

    private void ensureCollection() {
        if (!collectionCreated) {
            synchronized (this) {
                if (!collectionCreated) {
                    Collections.VectorParams params = Collections.VectorParams.newBuilder()
                            .setSize(llmProperties.embeddingDimension())
                            .setDistance(Collections.Distance.Cosine)
                            .build();
                    bestEffort(() -> client().createCollectionAsync(cacheProperties.collectionName(), params),
                            "ensure semantic cache collection");
                    collectionCreated = true;
                }
            }
        }
    }

    private QdrantEmbeddingStore storeFor() {
        return new QdrantEmbeddingStore(client(), cacheProperties.collectionName(), PAYLOAD_SELECTOR);
    }

    static String deterministicId(UUID docSetId, String question) {
        return UUID.nameUUIDFromBytes((docSetId + ":" + question).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static Filter docSetFilter(UUID docSetId) {
        return MetadataFilterBuilder.metadataKey(META_DOC_SET).isEqualTo(docSetId.toString());
    }

    private QdrantClient client() {
        QdrantClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                            qdrantProperties.host(), qdrantProperties.grpcPort(), false);
                    if (qdrantProperties.apiKey() != null && !qdrantProperties.apiKey().isBlank()) {
                        builder.withApiKey(qdrantProperties.apiKey());
                    }
                    local = new QdrantClient(builder.build());
                    client = local;
                }
            }
        }
        return local;
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