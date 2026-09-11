package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryManifestSupport;
import com.dbts.glyahhaigeneratecode.core.rag.file.RagSourceFileReader;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagSourceFile;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentDiskChunkFilterTest {

    @Mock
    private RagSourceFileReader sourceFileReader;

    @Test
    void filter_readsSameFileOnceAndPreservesMatchingCandidateOrder() {
        RagCodeChunkCandidate first = candidate("src/App.vue", 0, "current");
        RagCodeChunkCandidate second = candidate("src/App.vue", 1, "current");
        when(sourceFileReader.read(any(Path.class), eq("src/App.vue")))
                .thenReturn(Optional.of(new RagSourceFile(
                        Path.of("src/App.vue"), "src/App.vue", "content", "current")));
        CurrentDiskChunkFilter filter = new CurrentDiskChunkFilter(sourceFileReader);

        List<RagCodeChunkCandidate> results = filter.filter(
                42L, CodeGenTypeEnum.VUE, List.of(first, second));

        assertEquals(List.of(first, second), results);
        ArgumentCaptor<Path> rootCaptor = ArgumentCaptor.forClass(Path.class);
        verify(sourceFileReader, times(1)).read(rootCaptor.capture(), eq("src/App.vue"));
        assertEquals(
                ConversationMemoryManifestSupport.resolveProjectRoot(42L, CodeGenTypeEnum.VUE)
                        .toAbsolutePath().normalize(),
                rootCaptor.getValue());
        assertThrows(UnsupportedOperationException.class, () -> results.add(first));
    }

    @Test
    void filter_discardsMissingInvalidAndStaleCandidates() {
        RagCodeChunkCandidate blankPath = candidate("", 0, "current");
        RagCodeChunkCandidate blankHash = candidate("src/Blank.vue", 0, "");
        RagCodeChunkCandidate missing = candidate("src/Missing.vue", 0, "current");
        RagCodeChunkCandidate stale = candidate("src/App.vue", 0, "old");
        when(sourceFileReader.read(any(Path.class), eq("src/Missing.vue"))).thenReturn(Optional.empty());
        when(sourceFileReader.read(any(Path.class), eq("src/App.vue")))
                .thenReturn(Optional.of(new RagSourceFile(Path.of("src/App.vue"), "src/App.vue", "new", "current")));

        List<RagCodeChunkCandidate> results = new CurrentDiskChunkFilter(sourceFileReader).filter(
                42L, CodeGenTypeEnum.VUE, List.of(blankPath, blankHash, missing, stale));

        assertEquals(List.of(), results);
    }

    private static RagCodeChunkCandidate candidate(String filePath, int chunkIndex, String hash) {
        return new RagCodeChunkCandidate(filePath, chunkIndex, "chunk", hash, "vue", 1, 2, 0.9f);
    }
}
