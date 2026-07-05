package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.config.ConversationMemoryProperties;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryRefArchiver;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryStateCache;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryStateInjectSupport;
import com.dbts.glyahhaigeneratecode.service.impl.CodeChunkIndexService;
import com.dbts.glyahhaigeneratecode.mapper.ChatHistoryMapper;
import com.dbts.glyahhaigeneratecode.mapper.ConversationMemoryRefMapper;
import com.dbts.glyahhaigeneratecode.mapper.SnapshotHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.util.ReflectionTestUtils.getField;
import static org.mockito.Mockito.mock;

class ConversationMemoryStateServiceImplTransactionTest {

    @Test
    void constructor_shouldRequireTransactionTemplateForSnapshotAndStateAtomicWrite() {
        ConversationMemoryStateServiceImpl service = new ConversationMemoryStateServiceImpl(
                mock(ConversationMemoryStateCache.class),
                mock(ConversationMemoryRefArchiver.class),
                mock(ConversationMemoryStateInjectSupport.class),
                mock(ChatHistoryMapper.class),
                mock(SnapshotHistoryMapper.class),
                mock(ConversationMemoryRefMapper.class),
                new ConversationMemoryProperties(),
                mock(TransactionTemplate.class),
                mock(CodeChunkIndexService.class)
        );

        assertNotNull(getField(service, "transactionTemplate"));
    }
}
