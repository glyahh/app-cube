# 用户个性化配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 在个人设置页新增「个性化」板块，让用户配置应用风格与回答风格 prompt，Redis 缓存 + MySQL 持久化，注入到代码生成链路 userMessage。

**Architecture:** 后端标准 controller→service→mapper 分层 + Redis cache-aside；前端在 UserSettings.vue 新增菜单项与表单；注入点在 ChatToGenCodeImpl（同时覆盖传统与 workflow 路径）。

**Tech Stack:** Spring Boot 3.5 / Java 21, MyBatis-Flex 1.11.1, Redis (StringRedisTemplate), Vue 3 / Ant Design Vue 4, Axios

---
## 模块划分与设计原因

| 模块 | 设计原因 | 涉及知识点 |
|------|----------|-----------|
| 1. SQL DDL | 独立演进, 不侵入现有建表脚本 | InnoDB, 唯一索引, 逻辑删除, Snowflake |
| 2. Entity + Mapper | MyBatis-Flex ORM 映射标准一层 | `@Table`, `@Id` Snowflake, `BaseMapper`, 逻辑删除注解 |
| 3. Service + Redis 缓存 | 缓存隔离：业务逻辑与持久层解耦，独立管理缓存策略 | Cache-aside, 穿透/击穿/雪崩防护, StringRedisTemplate, Jackson |
| 4. Controller | RESTful 接口，使用现有 @MyRole/ThrowUtils 规范 | BaseResponse, ThrowUtils, session 登录态 |
| 5. ChatToGenCodeImpl 注入 | 统一入口：一个点同时覆盖传统与 workflow 路径 | 字符串拼接、空配置跳过 |
| 6. 前端 UserSettings.vue | 现有个人设置页新增菜单项与表单，复用已有组件与样式 | Ant Design Vue 表单、Vue 组件化、TypeScript API 调用 |

## 数据流设计

```
PUT /user/personalization → Controller → Service.saveOrUpdate()
                                        → MySQL upsert
                                        → Redis delete（删除而非更新，避免不一致）

GET /user/personalization → Controller → Service.getByUserId()
                                        → Redis GET（命中返回）
                                        → MySQL SELECT（未命中回源，回填 Redis，TTL + 随机抖动）

chatToGenCode(message, user) → ChatToGenCodeImpl.injectPromptIfPresent(message, userId)
                              → Service.getByUserId(userId)（带缓存）
                              → 拼接 tagBlock + message → 传给 Facade
```

**为什么选这个方案：**
- 在 ChatToGenCodeImpl 注入而非 Facade：因为传统和 workflow 两个 facade 各自独立，在 ChatToGenCodeImpl 中统一注入只需改一处。
- Cache-aside（读时回填 + 写时删缓存）：避免同时更新 MySQL 和 Redis 导致数据不一致，写操作只需删缓存即可保证后续读命中时取得最新值。
- Redis key 格式 `user:favourite:style:{userId} / user:favourite:app:{userId}`：既有项目 `chat:echo_memory:{appId}` 采用类似 `domain:subdomain:id` 命名，保持风格一致。

## 关键决策点与替代方案

| 决策 | 选用方案 | 替代方案 | 取舍 |
|------|---------|---------|------|
| 注入点 | ChatToGenCodeImpl | AiCodeGeneratorFacade | Facade 有两个（传统+workflow），ChatToGenCodeImpl 统一入口更干净 |
| 缓存更新 | 写时 delete | 写时 update | delete 防止并发写入不一致，且多数用户不常改配置，delete 开销更小 |
| 空值防御 | 短 TTL "{}" | 布隆过滤器 | 布隆需要预热所有 userId，本项目用户量小，短 TTL 更简单可靠 |
| 过期时间 | 2h + 随机抖动 | 固定 24h / 30min | 配置项不常变更，2h 是合理平衡；随机 10min 抖动防雪崩 |
| 逻辑删除 | isDelete 同项目风格 | 物理删除 | 项目现有 User 实体 isDelete 惯例，保持一致 |

---

### Task 1: SQL DDL 文件

**设计原因：** 独立增量 SQL 文件，与现有 sql/conversation_memory_tables.sql 风格一致。

**涉及知识点：** InnoDB 引擎、唯一索引、逻辑删除、Snowflake 主键、ON UPDATE CURRENT_TIMESTAMP

**Files:** Create: `sql/user_personalization.sql`

- [ ] **Step 1: 创建 SQL 文件**

```sql
-- sql/user_personalization.sql
-- 增量 DDL：用户个性化配置表（每用户一条记录，userId 唯一约束）
CREATE TABLE IF NOT EXISTS user_personalization (
    id          BIGINT       NOT NULL PRIMARY KEY COMMENT '主键（Snowflake）',
    userId      BIGINT       NOT NULL COMMENT '用户ID',
    app_style   TEXT                  COMMENT '应用风格 prompt：控制生成的前端视觉与结构偏好',
    answer_style TEXT                 COMMENT '回答风格 prompt：控制 AI 自然语言回复的语气与格式',
    createTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    UNIQUE KEY uk_userId (userId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户个性化配置';
```

- [ ] **Step 2: 验证**

Run: `Get-Content sql/user_personalization.sql`

---

### Task 2: Entity + Mapper（ORM 层）

**设计原因：** MyBatis-Flex 规范下每表对应一个 Entity + Mapper，与 User.java / UserMapper.java 风格一致。

**涉及知识点：** @Table、@Id Snowflake、BaseMapper、@Column 字段映射、isLogicDelete

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/model/Entity/UserPersonalization.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/mapper/UserPersonalizationMapper.java`

- [ ] **Step 1: 创建 Entity**

```java
package com.dbts.glyahhaigeneratecode.model.Entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Table("user_personalization")
public class UserPersonalization implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("userId") private Long userId;
    @Column("app_style") private String appStyle;
    @Column("answer_style") private String answerStyle;
    @Column("createTime") private LocalDateTime createTime;
    @Column("updateTime") private LocalDateTime updateTime;
    @Column(value = "isDelete", isLogicDelete = true) private Integer isDelete;
}
```

- [ ] **Step 2: 创建 Mapper**

```java
package com.dbts.glyahhaigeneratecode.mapper;
import com.dbts.glyahhaigeneratecode.model.Entity.UserPersonalization;
import com.mybatisflex.core.BaseMapper;
public interface UserPersonalizationMapper extends BaseMapper<UserPersonalization> {}
```

- [ ] **Step 3: 验证编译** `.\mvnw.cmd -q -DskipTests compile`

---

### Task 3: DTO + VO + 常量类

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/UserPersonalizationUpdateRequest.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/model/VO/UserPersonalizationVO.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/constant/UserPersonalizationConstant.java`

- [ ] **Step 1: DTO**

```java
package com.dbts.glyahhaigeneratecode.model.DTO;
import lombok.Data;
@Data
public class UserPersonalizationUpdateRequest {
    private String appStyle;
    private String answerStyle;
}
```

- [ ] **Step 2: VO**

```java
package com.dbts.glyahhaigeneratecode.model.VO;
import lombok.Data;
@Data
public class UserPersonalizationVO {
    private String appStyle;
    private String answerStyle;
}
```

- [ ] **Step 3: 常量类**

```java
package com.dbts.glyahhaigeneratecode.constant;
import java.time.Duration;
public final class UserPersonalizationConstant {
    private UserPersonalizationConstant() {}
    public static final String REDIS_KEY_PREFIX = "user:pers:";
    public static final long CACHE_TTL_SECONDS = Duration.ofHours(2).toSeconds();
    public static final long CACHE_TTL_JITTER_SECONDS = Duration.ofMinutes(10).toSeconds();
    public static final long CACHE_NULL_TTL_SECONDS = 60;
    public static final String CACHE_NULL_PLACEHOLDER = "{}";
    public static final int PROMPT_MAX_LENGTH = 2000;
    public static final String INJECT_TAG_APP_STYLE = "[user_app_style]";
    public static final String INJECT_TAG_ANSWER_STYLE = "[user_answer_style]";
}
```

- [ ] **Step 4: 验证编译** `.\mvnw.cmd -q -DskipTests compile`

---

### Task 4: Service 接口 + 实现（含 Redis 缓存）

**设计原因：** Service 封装缓存+持久化，Controller 和 ChatToGenCodeImpl 只依赖接口不感知实现细节。

**涉及知识点：** StringRedisTemplate、ObjectMapper JSON、cache-aside、随机 TTL 抖动、synchronized 锁防击穿

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/service/UserPersonalizationService.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/UserPersonalizationServiceImpl.java`

- [ ] **Step 1: Service 接口**

```java
package com.dbts.glyahhaigeneratecode.service;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;

public interface UserPersonalizationService {
    UserPersonalizationVO getByUserId(Long userId);
    void saveOrUpdate(Long userId, UserPersonalizationUpdateRequest request);
    String buildInjectPrompt(Long userId);
}
```

- [ ] **Step 2: Service 实现（核心文件，完整代码见附录）**

```java
package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.UserPersonalizationConstant;
import com.dbts.glyahhaigeneratecode.mapper.UserPersonalizationMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.UserPersonalization;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class UserPersonalizationServiceImpl implements UserPersonalizationService {
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};
    private final UserPersonalizationMapper mapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public UserPersonalizationVO getByUserId(Long userId) {
        if (userId == null || userId <= 0) return new UserPersonalizationVO();
        String key = buildRedisKey(userId);
        String cachedJson = stringRedisTemplate.opsForValue().get(key);
        // 命中缓存
        if (StrUtil.isNotBlank(cachedJson)) {
            if (UserPersonalizationConstant.CACHE_NULL_PLACEHOLDER.equals(cachedJson)) {
                refreshTtlWithJitter(key, UserPersonalizationConstant.CACHE_NULL_TTL_SECONDS);
                return new UserPersonalizationVO();
            }
            try {
                Map<String, String> map = objectMapper.readValue(cachedJson, MAP_TYPE);
                refreshTtlWithJitter(key, UserPersonalizationConstant.CACHE_TTL_SECONDS);
                return toVO(map);
            } catch (Exception e) {
                log.warn("反序列化缓存失败 userId={}", userId, e);
            }
        }
        // 同步锁防击穿
        synchronized (userId.toString().intern()) {
            String recheck = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(recheck) && !UserPersonalizationConstant.CACHE_NULL_PLACEHOLDER.equals(recheck)) {
                try {
                    return toVO(objectMapper.readValue(recheck, MAP_TYPE));
                } catch (Exception e) { /* fall through */ }
            }
            UserPersonalization entity = selectByUserId(userId);
            if (entity != null) {
                Map<String, String> map = entityToMap(entity);
                try {
                    String json = objectMapper.writeValueAsString(map);
                    stringRedisTemplate.opsForValue().set(key, json,
                            UserPersonalizationConstant.CACHE_TTL_SECONDS + RandomUtil.randomLong(0, UserPersonalizationConstant.CACHE_TTL_JITTER_SECONDS),
                            TimeUnit.SECONDS);
                } catch (Exception e) { log.warn("缓存回填失败 userId={}", userId, e); }
                return toVO(map);
            } else {
                stringRedisTemplate.opsForValue().set(key, UserPersonalizationConstant.CACHE_NULL_PLACEHOLDER,
                        UserPersonalizationConstant.CACHE_NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return new UserPersonalizationVO();
            }
        }
    }

    @Override
    public void saveOrUpdate(Long userId, UserPersonalizationUpdateRequest request) {
        String appStyle = StrUtil.maxLength(request.getAppStyle(), UserPersonalizationConstant.PROMPT_MAX_LENGTH);
        String answerStyle = StrUtil.maxLength(request.getAnswerStyle(), UserPersonalizationConstant.PROMPT_MAX_LENGTH);
        UserPersonalization entity = selectByUserId(userId);
        if (entity == null) {
            mapper.insertSelective(UserPersonalization.builder().userId(userId).appStyle(appStyle).answerStyle(answerStyle).build());
        } else {
            entity.setAppStyle(appStyle);
            entity.setAnswerStyle(answerStyle);
            mapper.update(entity);
        }
        try { stringRedisTemplate.delete(buildRedisKey(userId)); } catch (Exception e) { log.warn("删缓存失败 userId={}", userId, e); }
    }

    @Override
    public String buildInjectPrompt(Long userId) {
        if (userId == null || userId <= 0) return "";
        UserPersonalizationVO vo = getByUserId(userId);
        boolean hasApp = StrUtil.isNotBlank(vo.getAppStyle());
        boolean hasAns = StrUtil.isNotBlank(vo.getAnswerStyle());
        if (!hasApp && !hasAns) return "";
        StringBuilder sb = new StringBuilder("\n\n");
        if (hasApp) {
            sb.append(UserPersonalizationConstant.INJECT_TAG_APP_STYLE).append("\n");
            sb.append("（说明：以下为你的应用风格偏好，优先级低于本轮显式指令、高于系统默认）\n");
            sb.append("用户偏好：").append(vo.getAppStyle()).append("\n\n");
        }
        if (hasAns) {
            sb.append(UserPersonalizationConstant.INJECT_TAG_ANSWER_STYLE).append("\n");
            sb.append("（说明：以下为你的回答风格偏好，优先级低于本轮显式指令、高于系统默认）\n");
            sb.append("用户偏好：").append(vo.getAnswerStyle()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildRedisKey(Long userId) { return UserPersonalizationConstant.REDIS_KEY_PREFIX + userId; }
    private void refreshTtlWithJitter(String key, long base) {
        try { stringRedisTemplate.expire(key, base + RandomUtil.randomLong(0, UserPersonalizationConstant.CACHE_TTL_JITTER_SECONDS), TimeUnit.SECONDS); } catch (Exception e) { /* ignore */ }
    }
    private UserPersonalization selectByUserId(Long userId) {
        return mapper.selectOneByQuery(new QueryWrapper().eq(UserPersonalization::getUserId, userId));
    }
    private Map<String, String> entityToMap(UserPersonalization e) {
        Map<String, String> m = new HashMap<>(); m.put("appStyle", e.getAppStyle()); m.put("answerStyle", e.getAnswerStyle()); return m;
    }
    private UserPersonalizationVO toVO(Map<String, String> m) {
        UserPersonalizationVO v = new UserPersonalizationVO(); v.setAppStyle(m.get("appStyle")); v.setAnswerStyle(m.get("answerStyle")); return v;
    }
}
```

- [ ] **Step 3: 验证编译** `.\mvnw.cmd -q -DskipTests compile`

---

### Task 5: Controller（REST 端点）

**Files:** Create: `src/main/java/com/dbts/glyahhaigeneratecode/controller/UserPersonalizationController.java`

- [ ] **Step 1: 创建 Controller**

```java
package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/personalization")
@RequiredArgsConstructor @Slf4j
public class UserPersonalizationController {
    private final UserPersonalizationService userPersonalizationService;
    private final UserService userService;

    @GetMapping
    public BaseResponse<UserPersonalizationVO> getPersonalization(HttpServletRequest request) {
        User u = userService.getUserInSession(request);
        ThrowUtils.throwIf(u == null || u.getId() == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        return ResultUtils.success(userPersonalizationService.getByUserId(u.getId()));
    }

    @PutMapping
    public BaseResponse<Boolean> updatePersonalization(@RequestBody UserPersonalizationUpdateRequest req, HttpServletRequest request) {
        User u = userService.getUserInSession(request);
        ThrowUtils.throwIf(u == null || u.getId() == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR, "参数不能为空");
        userPersonalizationService.saveOrUpdate(u.getId(), req);
        return ResultUtils.success(true);
    }
}
```

- [ ] **Step 2: 验证编译** `.\mvnw.cmd -q -DskipTests compile`

---

### Task 6: ChatToGenCodeImpl 注入

**Files:** Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatToGenCodeImpl.java`

- [ ] **Step 1: 新增字段注入 + 私有方法**

在类字段区域添加：
```java
private final UserPersonalizationService userPersonalizationService;
```

新增私有方法（放在现有 validateParams / injectPersonalizationPrompt 后）：
```java
/**
 * 若用户有个性化配置，拼接为 userMessage 前缀注入。
 * 空配置时原样返回 message。
 */
private String injectPersonalizationPrompt(String message, Long userId) {
    String injectBlock = userPersonalizationService.buildInjectPrompt(userId);
    return StrUtil.isBlank(injectBlock) ? message : injectBlock + message;
}
```

- [ ] **Step 2: 在 chatToGenCode 方法调用 facade 前注入**

```java
// 第6步调用 facade 之前（message 传给 AI，原始 message 用于入库）
String enhancedMessage = injectPersonalizationPrompt(message, user.getId());
Flux<String> result = aiCodeGeneratorFacade.generateAndSaveCodeStream(enhancedMessage, codeGenTypeEnum, appId, firstRound);
```

注意：`enhancedMessage` 只传给 facade，`message` 保持原样用于入库。

- [ ] **Step 3: 在 chatToGenCodeByWorkflow 方法调用 facade 前注入**

```java
String enhancedMessage = injectPersonalizationPrompt(message, user.getId());
Flux<String> result = workflowCodeGeneratorFacade.generateAndSaveCodeStream(enhancedMessage, codeGenTypeEnum, appId, firstRound);
```

- [ ] **Step 4: 验证编译** `.\mvnw.cmd -q -DskipTests compile`

---

### Task 7: 前端 API 客户端

**Files:** Modify: `ai-generate-code-frontend/src/api/userController.ts`

- [ ] **Step 1: 添加 API 调用函数（或先尝试 npm run openapi2ts）**

在 userController.ts 末尾添加：

```typescript
export interface UserPersonalizationVO {
  appStyle?: string;
  answerStyle?: string;
}
export interface UserPersonalizationUpdateRequest {
  appStyle?: string;
  answerStyle?: string;
}

export async function userPersonalizationGetUsingGet(options?: Record<string, any>) {
  return request<UserPersonalizationVO>('/user/personalization', { method: 'GET', ...(options || {}) });
}

export async function userPersonalizationPutUsingPut(body: UserPersonalizationUpdateRequest, options?: Record<string, any>) {
  return request<boolean>('/user/personalization', { method: 'PUT', data: body, ...(options || {}) });
}
```

- [ ] **Step 2: 验证类型检查** `cd ai-generate-code-frontend && npx vue-tsc --build --noEmit`

---

### Task 8: 前端 UserSettings.vue 个性化面板

**Files:** Modify: `ai-generate-code-frontend/src/page/User/UserSettings.vue`

- [ ] **Step 1: Script 区 — 导入图标和 API**

在 import 区添加图标和 API：
```typescript
import { StarOutlined } from '@ant-design/icons-vue'
import { userPersonalizationGetUsingGet, userPersonalizationPutUsingPut } from '@/api/userController'
import type { UserPersonalizationVO, UserPersonalizationUpdateRequest } from '@/api/userController'
```

- [ ] **Step 2: MenuKey 和 menuItems**

将 `type MenuKey` 改为：
```typescript
type MenuKey = 'password' | 'profile' | 'apply' | 'history' | 'personalization'
```

在 menuItems 中（profile 之后，password 之前）插入：
```typescript
{ key: 'personalization', label: '个性化', icon: StarOutlined },
```

- [ ] **Step 3: 表单状态与逻辑**

追加状态变量：
```typescript
const personalizationForm = ref({ appStyle: '', answerStyle: '' })
const personalizationLoading = ref(false)

async function loadPersonalization() {
  try {
    const res = await userPersonalizationGetUsingGet()
    if (res?.data) {
      personalizationForm.value = { appStyle: res.data.appStyle ?? '', answerStyle: res.data.answerStyle ?? '' }
    }
  } catch (e: any) { message.error('加载失败') }
}

async function handlePersonalizationSubmit() {
  personalizationLoading.value = true
  try {
    const res = await userPersonalizationPutUsingPut({ appStyle: personalizationForm.value.appStyle, answerStyle: personalizationForm.value.answerStyle })
    if (res?.data === true) message.success('个性化配置已保存')
    else message.error('保存失败')
  } catch (e: any) { message.error('保存失败') }
  finally { personalizationLoading.value = false }
}
```

在 watch 中添加个性化加载：
```typescript
if (val === 'personalization') { void loadPersonalization() }
```

- [ ] **Step 4: 模板区 — 添加表单**

在 `activeMenu === 'password'` 和 `activeMenu === 'apply'` 之间插入：

```html
<section v-else-if="activeMenu === 'personalization'" class="work-panel">
  <h3 class="panel-title">个性化</h3>
  <p class="panel-desc">配置你的代码生成偏好，每次生成时 AI 将参考以下指引</p>
  <div class="form-card">
    <a-form layout="vertical" class="personalization-form">
      <a-form-item label="应用风格">
        <template #extra>控制 AI 生成的应用视觉、布局与结构偏好（优先级低于本轮指令）</template>
        <a-textarea v-model:value="personalizationForm.appStyle" placeholder="例如：我喜欢简洁、使用毛玻璃效果、主色调为蓝色..." :rows="5" size="large" :maxlength="2000" show-count />
      </a-form-item>
      <a-form-item label="回答风格">
        <template #extra>控制 AI 回复的语气、详细程度与格式偏好（优先级低于本轮指令）</template>
        <a-textarea v-model:value="personalizationForm.answerStyle" placeholder="例如：用正式的语气回答，代码与解释交替..." :rows="5" size="large" :maxlength="2000" show-count />
      </a-form-item>
      <a-button type="primary" size="large" :loading="personalizationLoading" class="submit-btn" @click="handlePersonalizationSubmit">保存配置</a-button>
    </a-form>
  </div>
</section>
```

- [ ] **Step 5: CSS 追加**

```css
.personalization-form { max-width: 520px; }
```

- [ ] **Step 6: 验证类型检查** `cd ai-generate-code-frontend && npx vue-tsc --build --noEmit`

---

### Task 9: 追加测试

**Files:**
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/service/impl/UserPersonalizationServiceImplTest.java`
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/controller/UserPersonalizationControllerTest.java`

- [ ] **Step 1: Service 单元测试**

```java
package com.dbts.glyahhaigeneratecode.service.impl;
import com.dbts.glyahhaigeneratecode.mapper.UserPersonalizationMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.UserPersonalization;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPersonalizationServiceImplTest {
    @Mock UserPersonalizationMapper mapper;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    UserPersonalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserPersonalizationServiceImpl(mapper, stringRedisTemplate, new ObjectMapper());
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test void getByUserId_null_returnsEmpty() {
        UserPersonalizationVO vo = service.getByUserId(null);
        assertNull(vo.getAppStyle()); assertNull(vo.getAnswerStyle());
    }

    @Test void buildInjectPrompt_noConfig_returnsEmpty() {
        when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(mapper.selectOneByQuery(any())).thenReturn(null);
        assertEquals("", service.buildInjectPrompt(1L));
    }

    @Test void buildInjectPrompt_hasConfig_returnsTaggedBlock() {
        when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(mapper.selectOneByQuery(any())).thenReturn(
                UserPersonalization.builder().userId(1L).appStyle("毛玻璃").answerStyle("简洁回答").build());
        String p = service.buildInjectPrompt(1L);
        assertTrue(p.contains("[user_app_style]")); assertTrue(p.contains("[user_answer_style]"));
    }

    @Test void saveOrUpdate_shouldDeleteCache() {
        when(stringRedisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(mapper.selectOneByQuery(any())).thenReturn(null);
        UserPersonalizationUpdateRequest req = new UserPersonalizationUpdateRequest();
        req.setAppStyle("dark");
        service.saveOrUpdate(1L, req);
        verify(stringRedisTemplate).delete(anyString());
    }
}
```

- [ ] **Step 2: Controller 单元测试**

```java
package com.dbts.glyahhaigeneratecode.controller;
import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.model.DTO.UserPersonalizationUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.UserPersonalizationVO;
import com.dbts.glyahhaigeneratecode.service.UserPersonalizationService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPersonalizationControllerTest {
    @Mock UserPersonalizationService userPersonalizationService;
    @Mock UserService userService;
    @Mock HttpServletRequest request;
    UserPersonalizationController controller;

    @BeforeEach
    void setUp() { controller = new UserPersonalizationController(userPersonalizationService, userService); }

    @Test void get_returnsVO() {
        User u = new User(); u.setId(1L);
        when(userService.getUserInSession(any())).thenReturn(u);
        UserPersonalizationVO mock = new UserPersonalizationVO(); mock.setAppStyle("modern");
        when(userPersonalizationService.getByUserId(1L)).thenReturn(mock);
        assertEquals("modern", controller.getPersonalization(request).getData().getAppStyle());
    }

    @Test void put_succeeds() {
        User u = new User(); u.setId(1L);
        when(userService.getUserInSession(any())).thenReturn(u);
        UserPersonalizationUpdateRequest req = new UserPersonalizationUpdateRequest();
        req.setAppStyle("dark");
        assertTrue(controller.updatePersonalization(req, request).getData());
    }
}
```

- [ ] **Step 3: 运行测试** `.\mvnw.cmd test -Dtest="UserPersonalizationServiceImplTest,UserPersonalizationControllerTest" -q`
Expected: Tests run: 6, Failures: 0

---

### Task 10: 最终验证

- [ ] **后端编译** `.\mvnw.cmd -q -DskipTests compile`
- [ ] **后端单测** `.\mvnw.cmd test -Dtest="UserPersonalizationServiceImplTest,UserPersonalizationControllerTest" -q`
- [ ] **前端类型检查** `cd ai-generate-code-frontend && npx vue-tsc --build --noEmit`（应无新增错误）

