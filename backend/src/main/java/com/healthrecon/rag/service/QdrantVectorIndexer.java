package com.healthrecon.rag.service;

import com.google.common.util.concurrent.ListenableFuture;
import com.healthrecon.rag.config.LlmProperties;
import com.healthrecon.rag.config.QdrantProperties;
import com.healthrecon.rag.config.SearchProperties;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.service.chunking.TextChunk;
import com.healthrecon.rag.service.search.Reranker;
import com.healthrecon.rag.service.search.SparseVectorizer;
import com.healthrecon.rag.service.search.SparseVectorizer.SparseVector;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Vector storage backed by Qdrant over gRPC. Each document set gets its own
 * collection (named after the set UUID) so search is scoped without extra
 * filters. Points carry two named vectors:
 * <ul>
 *   <li>{@code dense} — the model embedding (cosine)</li>
 *   <li>{@code bm25} — a client-side BM25-style sparse vector
 *       ({@code modifier: idf} applied server-side)</li>
 * </ul>
 * Search is hybrid by default: dense + sparse prefetches fused with
 * reciprocal rank fusion, then an optional reranker (config-gated). Metadata
 * keys are stored as plain strings to survive the payload round-trip.
 */
@Component
public class QdrantVectorIndexer implements VectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorIndexer.class);
    private static final Duration OP_TIMEOUT = Duration.ofSeconds(30);

    private static final String META_DOC_ID = "doc_id";
    private static final String META_FILENAME = "filename";
    private static final String META_SECTION = "section";
    private static final String META_TEXT = "text";

    private static final String VECTOR_DENSE = "dense";
    private static final String VECTOR_BM25 = "bm25";

    private final QdrantProperties properties;
    private final LlmProperties llmProperties;
    private final SearchProperties searchProperties;
    private final Reranker reranker;
    private final SparseVectorizer vectorizer;
    private volatile QdrantClient client;

    public QdrantVectorIndexer(QdrantProperties properties,
                               LlmProperties llmProperties,
                               SearchProperties searchProperties,
                               Reranker reranker,
                               SparseVectorizer vectorizer) {
        this.properties = properties;
        this.llmProperties = llmProperties;
        this.searchProperties = searchProperties;
        this.reranker = reranker;
        this.vectorizer = vectorizer;
    }

    @Override
    public void ensureCollection(UUID docSetId) {
        Collections.VectorParams dense = Collections.VectorParams.newBuilder()
                .setSize(llmProperties.embeddingDimension())
                .setDistance(Collections.Distance.Cosine)
                .build();
        Collections.VectorParamsMap denseMap = Collections.VectorParamsMap.newBuilder()
                .putMap(VECTOR_DENSE, dense)
                .build();
        Collections.SparseVectorConfig sparse = Collections.SparseVectorConfig.newBuilder()
                .putMap(VECTOR_BM25, Collections.SparseVectorParams.newBuilder()
                        .setModifier(Collections.Modifier.Idf)
                        .build())
                .build();
        Collections.CreateCollection request = Collections.CreateCollection.newBuilder()
                .setCollectionName(collectionName(docSetId))
                .setVectorsConfig(Collections.VectorsConfig.newBuilder().setParamsMap(denseMap))
                .setSparseVectorsConfig(sparse)
                .build();
        bestEffort(() -> client().createCollectionAsync(request), "ensure collection");
    }

    @Override
    public void upsert(StoredDocument doc, List<TextChunk> chunks, List<TextSegment> segments, List<Embedding> embeddings) {
        ensureCollection(doc.getDocSetId());
        String collection = collectionName(doc.getDocSetId());
        deleteVoid(() -> deleteDocFilter(collection, doc.getId()), "remove previous vectors");
        if (chunks.isEmpty()) {
            return;
        }
        List<Points.PointStruct> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            TextSegment segment = segments.get(i);
            Embedding embedding = embeddings.get(i);
            points.add(pointFor(doc, chunk, segment, embedding));
        }
        bestEffort(() -> client().upsertAsync(collection, points), "upsert chunk vectors");
    }

    @Override
    public void deleteDocument(UUID docSetId, UUID docId) {
        deleteVoid(() -> deleteDocFilter(collectionName(docSetId), docId), "delete document vectors");
    }

    @Override
    public void deleteSet(UUID docSetId) {
        bestEffort(() -> client().deleteCollectionAsync(collectionName(docSetId)), "delete collection");
    }

    @Override
    public List<ChunkSearchHit> search(UUID docSetId, String queryText, Embedding queryEmbedding, int topK) {
        String collection = collectionName(docSetId);
        List<ChunkSearchHit> fused = searchFused(collection, queryText, queryEmbedding);
        return reranker.rerank(docSetId, queryText, fused, topK);
    }

    private List<ChunkSearchHit> searchFused(String collection, String queryText, Embedding queryEmbedding) {
        List<Float> dense = toList(queryEmbedding);
        SparseVector sparse = vectorizer.vectorize(queryText);

        Points.QueryPoints.Builder request = Points.QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true))
                .setLimit(Math.max(1, searchProperties.candidates()));

        boolean hybrid = searchProperties.hybridEnabled() && !sparse.isEmpty();
        if (hybrid) {
            request.addPrefetch(Points.PrefetchQuery.newBuilder()
                            .setQuery(QueryFactory.nearest(dense))
                            .setUsing(VECTOR_DENSE)
                            .setLimit(Math.max(1, searchProperties.candidates())))
                    .addPrefetch(Points.PrefetchQuery.newBuilder()
                            .setQuery(QueryFactory.nearest(toFloatList(sparse.values()), toIntList(sparse.indices())))
                            .setUsing(VECTOR_BM25)
                            .setLimit(Math.max(1, searchProperties.candidates())));
            Points.Fusion fusion = "dbsf".equals(searchProperties.fusion()) ? Points.Fusion.DBSF : Points.Fusion.RRF;
            request.setQuery(QueryFactory.fusion(fusion));
        } else {
            request.setQuery(QueryFactory.nearest(dense)).setUsing(VECTOR_DENSE);
        }

        try {
            List<Points.ScoredPoint> scored = client().queryAsync(request.build(), OP_TIMEOUT)
                    .get(OP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return scored.stream().map(this::toHit).toList();
        } catch (Exception e) {
            log.warn("Hybrid search failed for collection {}: {}", collection, e.getMessage());
            return List.of();
        }
    }

    private Points.PointStruct pointFor(StoredDocument doc, TextChunk chunk, TextSegment segment, Embedding embedding) {
        java.util.Map<String, Points.Vector> vectorMap = new java.util.HashMap<>();
        vectorMap.put(VECTOR_DENSE, VectorFactory.vector(toList(embedding)));
        SparseVector sparse = vectorizer.vectorize(chunk.text());
        if (!sparse.isEmpty()) {
            vectorMap.put(VECTOR_BM25, VectorFactory.vector(
                    toFloatList(sparse.values()), toIntList(sparse.indices())));
        }
        return Points.PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.fromString(deterministicId(doc.getId(), chunk.chunkIndex()))))
                .setVectors(VectorsFactory.namedVectors(vectorMap))
                .putAllPayload(payload(doc, chunk))
                .build();
    }

    private java.util.Map<String, JsonWithInt.Value> payload(StoredDocument doc, TextChunk chunk) {
        java.util.Map<String, JsonWithInt.Value> payload = new java.util.HashMap<>();
        payload.put(META_DOC_ID, stringValue(doc.getId().toString()));
        payload.put(META_FILENAME, stringValue(doc.getFilename()));
        payload.put(META_SECTION, stringValue(chunk.section() == null ? "" : chunk.section()));
        payload.put(META_TEXT, stringValue(chunk.text()));
        payload.put("chunk_index", JsonWithInt.Value.newBuilder().setIntegerValue(chunk.chunkIndex()).build());
        return payload;
    }

    private static JsonWithInt.Value stringValue(String value) {
        return JsonWithInt.Value.newBuilder().setStringValue(value).build();
    }

    private ChunkSearchHit toHit(Points.ScoredPoint point) {
        java.util.Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
        return new ChunkSearchHit(
                safeUuid(payload.containsKey(META_DOC_ID) ? payload.get(META_DOC_ID).getStringValue() : null),
                payload.containsKey(META_FILENAME) ? payload.get(META_FILENAME).getStringValue() : null,
                payload.containsKey(META_SECTION) ? payload.get(META_SECTION).getStringValue() : null,
                point.getScore(),
                payload.containsKey(META_TEXT) ? payload.get(META_TEXT).getStringValue() : null);
    }

    private void deleteDocFilter(String collection, UUID docId) throws Exception {
        Common.Filter filter = Common.Filter.newBuilder()
                .addAllMust(List.of(ConditionFactory.matchKeyword(META_DOC_ID, docId.toString())))
                .build();
        client().deleteAsync(collection, filter, OP_TIMEOUT).get(OP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    static String deterministicId(UUID docId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((docId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static TextSegment segmentFor(StoredDocument doc, TextChunk chunk) {
        return TextSegment.from(chunk.text());
    }

    private static List<Float> toList(Embedding embedding) {
        List<Float> values = new ArrayList<>(embedding.vector().length);
        for (float value : embedding.vector()) {
            values.add(value);
        }
        return values;
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }

    private static List<Integer> toIntList(int[] indices) {
        List<Integer> result = new ArrayList<>(indices.length);
        for (int index : indices) {
            result.add(index);
        }
        return result;
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