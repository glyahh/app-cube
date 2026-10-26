package com.dbts.glyahhaigeneratecode.integration;

import com.dbts.glyahhaigeneratecode.mapper.UserPersonalizationMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.service.impl.UserPersonalizationServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RedisCacheIntegrationTest extends InfrastructureIntegrationTest {
    @BeforeAll
    static void startContainers() {
        startInfrastructure();
    }

    @Autowired
    private UserPersonalizationMapper mapper;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void cacheBackfillAndInvalidationUseRealPostgresAndRedis() {
        String key = "user:favourite:app:7002";
        redis.delete(key);
        var service = new UserPersonalizationServiceImpl(mapper, redis);
        long userId = 7002;
        jdbc.update("INSERT INTO biz.\"user\"(id,user_account,user_password) VALUES(?,'cache-owner','irreversible-test-hash')", userId);
        jdbc.update("INSERT INTO biz.user_personalization(id,user_id,app_style,answer_style) VALUES(?,?,?,?)",
                8002, userId, "from-postgres", "concise");

        assertEquals("from-postgres", service.getCachedAppStyle(userId));
        assertEquals("from-postgres", redis.opsForValue().get(key));
        var request = new UserPersonalizationUpdateRequest();
        request.setAppStyle("updated");
        request.setAnswerStyle("concise");
        service.saveOrUpdate(userId, request);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        assertEquals("updated", service.getCachedAppStyle(userId));
        assertEquals("updated", redis.opsForValue().get(key));
    }

    @Test
    void missingPreferenceCachesNullInsteadOfThrowing() {
        var service = new UserPersonalizationServiceImpl(mapper, redis);
        assertNull(service.getCachedAppStyle(987654321L));
        assertEquals("{}", redis.opsForValue().get("user:favourite:app:987654321"));
    }
}
