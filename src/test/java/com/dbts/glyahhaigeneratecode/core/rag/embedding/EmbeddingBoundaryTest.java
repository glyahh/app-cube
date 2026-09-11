package com.dbts.glyahhaigeneratecode.core.rag.embedding;

import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingBoundaryTest {

    @Test
    void adapter_restoresResponseOrderAndRejectsWrongDimension() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        String body = "{\"data\":["
                + "{\"index\":1,\"embedding\":[0.5,0.6]},"
                + "{\"index\":0,\"embedding\":[0.1]}]}";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
        EmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                "http://localhost/v1", "key", "model", 2, restTemplate, new ObjectMapper());

        float[][] result = client.embedBatch(List.of("bad", "good"));

        assertNull(result[0]);
        assertNotNull(result[1]);
        assertEquals(0.5f, result[1][0], 0.001f);
    }

    @Test
    void service_filtersBlankInputsKeepsOrderAndSplitsBatches() {
        List<List<String>> batches = new ArrayList<>();
        EmbeddingClient client = inputs -> {
            batches.add(List.copyOf(inputs));
            float[][] vectors = new float[inputs.size()][];
            for (int i = 0; i < inputs.size(); i++) {
                vectors[i] = new float[]{inputs.get(i).length()};
            }
            return vectors;
        };
        EmbeddingService service = new EmbeddingService(2, 1, 0, client);

        float[][] result = service.embedBatch(List.of("one", " ", "three", "four"));

        assertEquals(List.of(List.of("one", "three"), List.of("four")), batches);
        assertEquals(4, result.length);
        assertEquals(3f, result[0][0]);
        assertNull(result[1]);
        assertEquals(5f, result[2][0]);
        assertEquals(4f, result[3][0]);
    }

    @Test
    void service_retriesOnceThenFailsSoftForWholeBatch() {
        AtomicInteger attempts = new AtomicInteger();
        EmbeddingClient client = inputs -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("unavailable");
        };
        EmbeddingService service = new EmbeddingService(10, 5, 0, client);

        float[][] result = service.embedBatch(List.of("first", "second"));

        assertEquals(2, attempts.get());
        assertNull(result[0]);
        assertNull(result[1]);
    }
}
