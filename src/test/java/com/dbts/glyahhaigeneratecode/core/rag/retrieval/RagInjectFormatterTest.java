package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryInjectTexts;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RagInjectFormatterTest {

    private static final String PREFIX = ConversationMemoryInjectTexts.INJECT_TAG
            + "\n以下为 ES 向量检索到的相关源码片段，已按相关性排序。以磁盘 readFile 为准。\n\n";

    @Test
    void format_emptyCandidatesReturnsNull() {
        assertNull(new RagInjectFormatter(new RagProperties()).format(List.of()));
    }

    @Test
    void format_preservesInjectTextHeaderAndTwoDecimalScore() {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setCharBudget(8000);
        RagCodeChunkCandidate candidate = new RagCodeChunkCandidate(
                "src/App.vue", 0, "const app = true;", "hash", "vue", 3, 7, 0.9f);

        String result = new RagInjectFormatter(properties).format(List.of(candidate));

        assertEquals(PREFIX
                + "=== src/App.vue (lines 3-7, score=0.90) ===\n"
                + "const app = true;\n\n", result);
    }

    @Test
    void format_budgetExactlyFitsEntryIncludesCompleteEntry() {
        String header = "=== src/App.vue (lines 1-2, score=1.00) ===";
        String content = "const app = true;";
        String entry = header + "\n" + content + "\n\n";
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setCharBudget(PREFIX.length() + entry.length());
        RagCodeChunkCandidate candidate = new RagCodeChunkCandidate(
                "src/App.vue", 0, content, "hash", "vue", 1, 2, 1.0f);

        String result = new RagInjectFormatter(properties).format(List.of(candidate));

        assertEquals(PREFIX + entry, result);
    }

    @Test
    void format_budgetOneCharacterShortOmitsSmallEntry() {
        String header = "=== src/App.vue (lines 1-2, score=1.00) ===";
        String content = "const app = true;";
        String entry = header + "\n" + content + "\n\n";
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setCharBudget(PREFIX.length() + entry.length() - 1);
        RagCodeChunkCandidate candidate = new RagCodeChunkCandidate(
                "src/App.vue", 0, content, "hash", "vue", 1, 2, 1.0f);

        String result = new RagInjectFormatter(properties).format(List.of(candidate));

        assertEquals(PREFIX, result);
    }

    @Test
    void format_truncatesToCalculatedLengthAndStopsBeforeNextEntry() {
        String header = "=== src/App.vue (lines 1-2, score=1.00) ===";
        String content = "x".repeat(500);
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setCharBudget(280);
        RagCodeChunkCandidate first = new RagCodeChunkCandidate(
                "src/App.vue", 0, content, "hash", "vue", 1, 2, 1.0f);
        RagCodeChunkCandidate second = new RagCodeChunkCandidate(
                "src/Other.vue", 0, "must-not-appear", "hash", "vue", 1, 2, 0.5f);

        String result = new RagInjectFormatter(properties).format(List.of(first, second));

        int expectedContentLength = 280 - PREFIX.length() - header.length() - 10;
        assertEquals(PREFIX + header + "\n" + "x".repeat(expectedContentLength)
                + "\n...(truncated)\n\n", result);
    }
}
