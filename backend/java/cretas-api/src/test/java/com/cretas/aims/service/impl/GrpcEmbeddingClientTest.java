package com.cretas.aims.service.impl;

import com.cretas.embedding.grpc.EmbeddingServiceGrpc;
import com.cretas.embedding.grpc.EmbeddingVector;
import com.cretas.embedding.grpc.EncodeBatchResponse;
import com.cretas.embedding.grpc.EncodeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcEmbeddingClientTest {

    @Test
    void batchUsesDedicatedDeadlineWhileSingleEncodeKeepsInteractiveDeadline() {
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub baseStub =
                mock(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub batchStub =
                mock(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub singleStub =
                mock(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        when(baseStub.withDeadlineAfter(15, TimeUnit.SECONDS)).thenReturn(batchStub);
        when(baseStub.withDeadlineAfter(3, TimeUnit.SECONDS)).thenReturn(singleStub);
        when(batchStub.encodeBatch(any())).thenReturn(EncodeBatchResponse.newBuilder()
                .setSuccess(true)
                .addEmbeddings(EmbeddingVector.newBuilder().addValues(1.0f).build())
                .build());
        when(singleStub.encode(any())).thenReturn(EncodeResponse.newBuilder()
                .setSuccess(true)
                .addEmbedding(2.0f)
                .build());
        GrpcEmbeddingClient client = new GrpcEmbeddingClient();
        ReflectionTestUtils.setField(client, "embeddingStub", baseStub);

        List<float[]> batchResult = client.encodeBatch(List.of("batch"));
        float[] singleResult = client.encode("single");

        assertEquals(1.0f, batchResult.get(0)[0]);
        assertEquals(2.0f, singleResult[0]);
        verify(baseStub).withDeadlineAfter(15, TimeUnit.SECONDS);
        verify(baseStub).withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    @Test
    void configuredBatchDeadlineIsAppliedWithoutChangingSingleEncode() {
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub baseStub =
                mock(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        EmbeddingServiceGrpc.EmbeddingServiceBlockingStub batchStub =
                mock(EmbeddingServiceGrpc.EmbeddingServiceBlockingStub.class);
        when(baseStub.withDeadlineAfter(21, TimeUnit.SECONDS)).thenReturn(batchStub);
        when(batchStub.encodeBatch(any())).thenReturn(EncodeBatchResponse.newBuilder()
                .setSuccess(true)
                .addEmbeddings(EmbeddingVector.newBuilder().addValues(1.0f).build())
                .build());
        GrpcEmbeddingClient client = new GrpcEmbeddingClient();
        ReflectionTestUtils.setField(client, "embeddingStub", baseStub);
        ReflectionTestUtils.setField(client, "batchRpcDeadlineSeconds", 21);

        client.encodeBatch(List.of("batch"));

        verify(baseStub).withDeadlineAfter(21, TimeUnit.SECONDS);
    }
}
