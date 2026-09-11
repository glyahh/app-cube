package com.dbts.glyahhaigeneratecode.core.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        service = new EmbeddingService(
                "http://localhost/v1", "key", "text-embedding-v4", 4,
                10, 1, 0, restTemplate, objectMapper);
    }

    @Test
    void embed_blank_returnsNull() {
        assertNull(service.embed(""));
        assertNull(service.embed("   "));
        assertNull(service.embed(null));
    }

    @Test
    void embed_apiSuccess_returnsVector() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        float[] result = service.embed("hello");

        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals(0.1f, result[0], 0.001f);

        @SuppressWarnings("rawtypes")
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://localhost/v1/embeddings"), requestCaptor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) requestCaptor.getValue().getBody();
        assertNotNull(requestBody);
        assertEquals("text-embedding-v4", requestBody.get("model"));
        assertEquals(List.of("hello"), requestBody.get("input"));
    }

    @Test
    void embedBatch_sendsOneRequestAndRestoresResponseOrderByIndex() {
        String body = "{\"data\":["
                + "{\"index\":1,\"embedding\":[0.5,0.6,0.7,0.8]},"
                + "{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        float[][] result = service.embedBatch(List.of("first", "second"));

        assertEquals(2, result.length);
        assertEquals(0.1f, result[0][0], 0.001f);
        assertEquals(0.5f, result[1][0], 0.001f);
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_splitsRequestsByConfiguredBatchSize() {
        EmbeddingService batchService = new EmbeddingService(
                "http://localhost/v1", "key", "text-embedding-v4", 4,
                2, 1, 0, restTemplate, objectMapper);
        String firstBody = "{\"data\":["
                + "{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]},"
                + "{\"index\":1,\"embedding\":[0.5,0.6,0.7,0.8]}]}";
        String secondBody = "{\"data\":["
                + "{\"index\":0,\"embedding\":[0.9,1.0,1.1,1.2]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(firstBody, HttpStatus.OK))
                .thenReturn(new ResponseEntity<>(secondBody, HttpStatus.OK));

        float[][] result = batchService.embedBatch(List.of("first", "second", "third"));

        assertEquals(3, result.length);
        assertEquals(0.9f, result[2][0], 0.001f);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_retriesRetryableStatusOnce() {
        EmbeddingService retryService = new EmbeddingService(
                "http://localhost/v1", "key", "text-embedding-v4", 4,
                10, 1, 0, restTemplate, objectMapper);
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        float[][] result = retryService.embedBatch(List.of("hello"));

        assertNotNull(result[0]);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_retriesNetworkFailureOnce() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        float[][] result = service.embedBatch(List.of("hello"));

        assertNotNull(result[0]);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_retriesBadRequestOnceThenReturnsNull() {
        EmbeddingService retryService = new EmbeddingService(
                "http://localhost/v1", "key", "text-embedding-v4", 4,
                10, 1, 0, restTemplate, objectMapper);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.BAD_REQUEST));

        float[][] result = retryService.embedBatch(List.of("hello"));

        assertNull(result[0]);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_retryExhaustedReturnsNullForWholeBatch() {
        EmbeddingService retryService = new EmbeddingService(
                "http://localhost/v1", "key", "text-embedding-v4", 4,
                10, 1, 0, restTemplate, objectMapper);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.BAD_GATEWAY));

        float[][] result = retryService.embedBatch(List.of("first", "second"));

        assertEquals(2, result.length);
        assertNull(result[0]);
        assertNull(result[1]);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_payloadTooLargeRetriesSameBatchOnce() {
        EmbeddingService batchService = new EmbeddingService(
                "http://localhost/v1", "key", "text-embedding-v4", 4,
                10, 1, 0, restTemplate, objectMapper);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.PAYLOAD_TOO_LARGE));

        float[][] result = batchService.embedBatch(List.of("first", "second"));

        assertNull(result[0]);
        assertNull(result[1]);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void embedBatch_dimensionMismatchReturnsNullForThatItem() {
        String body = "{\"data\":["
                + "{\"index\":0,\"embedding\":[0.1,0.2]},"
                + "{\"index\":1,\"embedding\":[0.5,0.6,0.7,0.8]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        float[][] result = service.embedBatch(List.of("bad", "good"));

        assertNull(result[0]);
        assertNotNull(result[1]);
    }

    @Test
    void embed_apiFailure_returnsNull() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR));

        assertNull(service.embed("hello"));
    }

    @Test
    void embed_exception_returnsNull() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("network"));

        assertNull(service.embed("hello"));
    }
}
