package com.cretas.aims.service.impl;

import com.cretas.aims.service.EmbeddingClient;
import com.cretas.embedding.grpc.*;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * gRPC-based Embedding Client
 *
 * Connects to a separate embedding-service via gRPC for text embedding and similarity computation.
 * This allows running the embedding model in a dedicated process with better resource management.
 *
 * Configuration:
 * - embedding.mode=grpc (to enable this client)
 * - grpc.client.embedding-service.address=static://localhost:9090
 * - grpc.client.embedding-service.negotiationType=plaintext
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Service
@ConditionalOnProperty(name = "embedding.mode", havingValue = "grpc")
@Slf4j
public class GrpcEmbeddingClient implements EmbeddingClient {

    private static final int DEFAULT_DIMENSION = 768;
    private static final int RPC_DEADLINE_SECONDS = 3;

    @Value("${cretas.embedding.grpc.batch-deadline-seconds:15}")
    private int batchRpcDeadlineSeconds = 15;

    @GrpcClient("embedding-service")
    private EmbeddingServiceGrpc.EmbeddingServiceBlockingStub embeddingStub;

    private volatile boolean available = false;
    private volatile int dimension = DEFAULT_DIMENSION;
    private volatile String modelName = "unknown";

    @PostConstruct
    public void init() {
        log.info("Initializing gRPC Embedding Client...");
        try {
            // Check service availability and get model info
            checkServiceAndGetInfo();
            log.info("gRPC Embedding Client initialized successfully. Model: {}, Dimension: {}",
                     modelName, dimension);
        } catch (Exception e) {
            log.warn("gRPC Embedding Service not available at startup: {}. Will retry on first use.",
                     e.getMessage());
        }
    }

    /**
     * Check service availability and retrieve model information
     */
    private void checkServiceAndGetInfo() {
        try {
            // Health check
            HealthCheckResponse healthResponse = embeddingStub
                    .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .healthCheck(
                    HealthCheckRequest.newBuilder().build());
            available = healthResponse.getAvailable();

            if (available) {
                // Get model info
                ModelInfoResponse infoResponse = embeddingStub
                        .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                        .getModelInfo(
                        ModelInfoRequest.newBuilder().build());
                modelName = infoResponse.getModelName();
                dimension = infoResponse.getDimension();
            }
        } catch (StatusRuntimeException e) {
            available = false;
            log.warn("gRPC service health check failed: {}", e.getStatus());
        }
    }

    @Override
    public float[] encode(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[dimension];
        }

        try {
            EncodeRequest request = EncodeRequest.newBuilder()
                    .setText(text)
                    .build();

            EncodeResponse response = embeddingStub
                    .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .encode(request);

            if (!response.getSuccess()) {
                log.error("Embedding encode failed: {}", response.getErrorMessage());
                throw new EmbeddingException("Embedding encode failed: " + response.getErrorMessage());
            }

            // Convert repeated float to float[]
            List<Float> embeddingList = response.getEmbeddingList();
            float[] result = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                result[i] = embeddingList.get(i);
            }

            // Update availability status on success
            if (!available) {
                available = true;
            }

            return result;

        } catch (StatusRuntimeException e) {
            available = false;
            log.error("gRPC call failed for encode: {}", e.getStatus());
            throw new EmbeddingException("gRPC embedding service unavailable: " + e.getStatus(), e);
        }
    }

    @Override
    public List<float[]> encodeBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            EncodeBatchRequest request = EncodeBatchRequest.newBuilder()
                    .addAllTexts(texts)
                    .build();

            EncodeBatchResponse response = embeddingStub
                    .withDeadlineAfter(batchRpcDeadlineSeconds, TimeUnit.SECONDS)
                    .encodeBatch(request);

            if (!response.getSuccess()) {
                log.error("Batch embedding encode failed: {}", response.getErrorMessage());
                throw new EmbeddingException("Batch embedding encode failed: " + response.getErrorMessage());
            }

            // Convert EmbeddingVector list to List<float[]>
            List<float[]> results = new ArrayList<>();
            for (EmbeddingVector vector : response.getEmbeddingsList()) {
                List<Float> values = vector.getValuesList();
                float[] arr = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    arr[i] = values.get(i);
                }
                results.add(arr);
            }

            // Update availability status on success
            if (!available) {
                available = true;
            }

            return results;

        } catch (StatusRuntimeException e) {
            available = false;
            log.error("gRPC call failed for encodeBatch: {}", e.getStatus());
            throw new EmbeddingException("gRPC embedding service unavailable: " + e.getStatus(), e);
        }
    }

    @Override
    public double computeSimilarity(String text1, String text2) {
        try {
            SimilarityRequest request = SimilarityRequest.newBuilder()
                    .setText1(text1)
                    .setText2(text2)
                    .build();

            SimilarityResponse response = embeddingStub
                    .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .computeSimilarity(request);

            if (!response.getSuccess()) {
                log.error("Compute similarity failed: {}", response.getErrorMessage());
                throw new EmbeddingException("Compute similarity failed: " + response.getErrorMessage());
            }

            // Update availability status on success
            if (!available) {
                available = true;
            }

            return response.getSimilarity();

        } catch (StatusRuntimeException e) {
            available = false;
            log.error("gRPC call failed for computeSimilarity: {}", e.getStatus());
            throw new EmbeddingException("gRPC embedding service unavailable: " + e.getStatus(), e);
        }
    }

    @Override
    public List<SimilarityResult> findSimilar(String query, List<String> candidates,
                                               double threshold, int topK) {
        try {
            FindSimilarRequest request = FindSimilarRequest.newBuilder()
                    .setQuery(query)
                    .addAllCandidates(candidates)
                    .setThreshold(threshold)
                    .setTopK(topK)
                    .build();

            FindSimilarResponse response = embeddingStub
                    .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .findSimilar(request);

            if (!response.getSuccess()) {
                log.error("Find similar failed: {}", response.getErrorMessage());
                throw new EmbeddingException("Find similar failed: " + response.getErrorMessage());
            }

            // Update availability status on success
            if (!available) {
                available = true;
            }

            // Convert to SimilarityResult list
            return response.getResultsList().stream()
                    .map(r -> new SimilarityResultImpl(r.getIndex(), r.getText(), r.getScore()))
                    .collect(Collectors.toList());

        } catch (StatusRuntimeException e) {
            available = false;
            log.error("gRPC call failed for findSimilar: {}", e.getStatus());
            throw new EmbeddingException("gRPC embedding service unavailable: " + e.getStatus(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        // Perform a fresh health check if not currently available
        if (!available) {
            checkServiceAndGetInfo();
        }
        return available;
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * SimilarityResult implementation
     */
    private static class SimilarityResultImpl implements SimilarityResult {
        private final int index;
        private final String text;
        private final double score;

        public SimilarityResultImpl(int index, String text, double score) {
            this.index = index;
            this.text = text;
            this.score = score;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public double getScore() {
            return score;
        }
    }
}
