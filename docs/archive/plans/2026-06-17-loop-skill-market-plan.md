# Loop Skill 市场 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完整实现 Loop（Skill）市场全链路：后端独立领域模型 + Redis 缓存注入、顶栏「Loop」市场页、用户菜单「我的 Loop」、标准 5 步模板创作/导入、首页多选入库、聊天页单选注入。

**Architecture:** 后端 LoopController→LoopService→Mapper 标准三层；LoopInjectService 在 ChatToGenCodeImpl 注入 tagged 块；Redis Cache-Aside 带反向索引；前端 4 新页 + GlobalHeader 导航 + Picker/InjectBar。

**Tech Stack:** Spring Boot 3.5 + Java 21 + MyBatis-Flex + Redis（后端）；Vue 3 + Vite 7 + Ant Design Vue 4（前端）

---

## 文件结构清单

### 后端新增/修改

| 文件 | 责任 |
|------|------|
| `sql/loop_tables.sql` | DDL：`loop`、`app_loop`、`user_loop_apply` 三表 |
| `model/Entity/Loop.java` | Loop 实体 |
| `model/DTO/LoopAddRequest.java` | 创建 Loop 请求体 |
| `model/DTO/LoopUpdateRequest.java` | 更新 Loop 请求体 |
| `model/DTO/LoopQueryRequest.java` | 分页查询，extends PageRequest，含 searchText |
| `model/VO/LoopVO.java` | Loop 视图对象 |
| `model/Entity/AppLoop.java` | AppLoop 实体 |
| `model/Entity/UserLoopApply.java` | 申请精选实体 |
| `mapper/LoopMapper.java` | Loop MyBatis-Flex Mapper |
| `mapper/AppLoopMapper.java` | AppLoop Mapper |
| `mapper/UserLoopApplyMapper.java` | 申请精选 Mapper |
| `service/LoopService.java` | Loop 领域服务接口 |
| `service/impl/LoopServiceImpl.java` | 实现：CRUD + 导入 + compiler 调用 + Redis |
| `service/AppLoopService.java` | 应用 Loop 库服务接口 |
| `service/impl/AppLoopServiceImpl.java` | 实现：bind/add/list/remove + Redis |
| `service/support/LoopInjectService.java` | SSE 注入服务 |
| `service/support/LoopWorkflowCompiler.java` | workflowJson→compiledPrompt 编译器 |
| `controller/LoopController.java` | Loop CRUD + 市场 + 审批端点 |
| `controller/AppLoopController.java` | AppLoop 库端点 |
| `model/DTO/AppAddRequest.java` | (修改) 增加 `List<Long> loopIds` |
| `service/impl/AppServiceImpl.java` | (修改) createApp 成功后内联 bindLoops |
| `service/impl/ChatToGenCodeImpl.java` | (修改) 注入 Loop |
| `controller/ChatToGenCodeController.java` | (修改) /gen/code 加 loopId 参数 |

### 前端新增/修改

| 文件 | 责任 |
|------|------|
| `src/page/Loop/LoopMarketView.vue` | 精选/探索市场（创建）|
| `src/page/Loop/MyLoopView.vue` | 我的 Loop 栅格（创建）|
| `src/page/Loop/LoopCreateView.vue` | 5步模板创作（创建）|
| `src/page/Loop/LoopEditView.vue` | 编辑（创建，可复用 Create）|
| `src/page/Admin/AdminLoopManage.vue` | 管理员 Loop 管理（创建）|
| `src/components/loop/LoopPickerTrigger.vue` | 首页多选器（创建）|
| `src/components/loop/AppLoopInjectBar.vue` | 聊天注入栏（创建）|
| `src/components/GlobalHeader.vue` | (修改) 导航项 |
| `src/router/index.ts` | (修改) 注册 Loop 路由 |
| `src/page/HomeView.vue` | (修改) 插入 LoopPickerTrigger |
| `src/page/App/AppChatView.vue` | (修改) 插入 AppLoopInjectBar + SSE loopId |
| `src/page/Admin/AdminApplyManage.vue` | (修改) 支持 user_loop_apply |

---

## Batch 1：数据层 + 基础工具（并行无依赖）

### Task 1.1: DDL + Loop Entity + Mapper

**Files:**
- Create: `sql/loop_tables.sql`
- Create: `model/entity/Loop.java`
- Create: `model/entity/AppLoop.java`
- Create: `model/entity/UserLoopApply.java`
- Create: `mapper/LoopMapper.java`
- Create: `mapper/AppLoopMapper.java`
- Create: `mapper/UserLoopApplyMapper.java`

- [ ] **Step 1: 写 DDL**

```sql
-- sql/loop_tables.sql

CREATE TABLE `loop` (
  `id` bigint NOT NULL COMMENT '雪花ID',
  `loop_name` varchar(128) NOT NULL COMMENT '名称',
  `description` varchar(512) DEFAULT '' COMMENT '市场简介',
  `cover` varchar(256) DEFAULT '' COMMENT '封面',
  `user_id` bigint NOT NULL COMMENT '创建者',
  `priority` int NOT NULL DEFAULT 0 COMMENT '精选阈值 >=99',
  `workflow_json` text COMMENT '标准模板步骤JSON',
  `compiled_prompt` text COMMENT '编译后的注入文本',
  `source_type` varchar(32) NOT NULL DEFAULT 'created' COMMENT 'created/imported',
  `visibility` varchar(32) NOT NULL DEFAULT 'private' COMMENT 'private/public',
  `is_delete` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_priority` (`priority`, `is_delete`, `visibility`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Loop技能表';

CREATE TABLE `app_loop` (
  `app_id` bigint NOT NULL,
  `loop_id` bigint NOT NULL,
  `added_from` varchar(32) NOT NULL DEFAULT 'creation' COMMENT 'creation/chat/market',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`app_id`, `loop_id`),
  KEY `idx_loop_id` (`loop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用Loop库';

CREATE TABLE `user_loop_apply` (
  `id` bigint NOT NULL COMMENT '雪花ID',
  `loop_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `operate` tinyint NOT NULL DEFAULT 1 COMMENT '1=申请精选',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2拒绝',
  `apply_reason` varchar(512) DEFAULT '',
  `review_user_id` bigint DEFAULT NULL,
  `review_remark` varchar(512) DEFAULT '',
  `review_time` datetime DEFAULT NULL,
  `is_delete` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_loop_id` (`loop_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Loop精选申请表';
```

- [ ] **Step 2: 写 Java 实体类 Loop.java**

```java
package com.dbts.glyahhaigeneratecode.model.entity;

import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Table("loop")
public class Loop {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private String loopName;
    private String description;
    private String cover;
    private Long userId;
    private Integer priority;
    private String workflowJson;
    private String compiledPrompt;
    private String sourceType;
    private String visibility;
    private Integer isDelete;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

```java
// model/entity/AppLoop.java
package com.dbts.glyahhaigeneratecode.model.entity;

import com.mybatisflex.annotation.Table;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Table("app_loop")
public class AppLoop {
    private Long appId;
    private Long loopId;
    private String addedFrom;
    private LocalDateTime createTime;
}
```

```java
// model/entity/UserLoopApply.java
package com.dbts.glyahhaigeneratecode.model.entity;

import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Table("user_loop_apply")
public class UserLoopApply {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long loopId;
    private Long userId;
    private Integer operate;
    private Integer status;
    private String applyReason;
    private Long reviewUserId;
    private String reviewRemark;
    private LocalDateTime reviewTime;
    private Integer isDelete;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 3: 写 Mapper 接口**

```java
// mapper/LoopMapper.java
package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.entity.Loop;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoopMapper extends BaseMapper<Loop> {
}
```

```java
// mapper/AppLoopMapper.java
package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.entity.AppLoop;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppLoopMapper extends BaseMapper<AppLoop> {
}
```

```java
// mapper/UserLoopApplyMapper.java
package com.dbts.glyahhaigeneratecode.mapper;

import com.dbts.glyahhaigeneratecode.model.entity.UserLoopApply;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserLoopApplyMapper extends BaseMapper<UserLoopApply> {
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code"
.\mvnw.cmd -q -DskipTests compile
```
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add sql/loop_tables.sql src/main/java/com/dbts/glyahhaigeneratecode/model/entity/Loop.java src/main/java/com/dbts/glyahhaigeneratecode/model/entity/AppLoop.java src/main/java/com/dbts/glyahhaigeneratecode/model/entity/UserLoopApply.java src/main/java/com/dbts/glyahhaigeneratecode/mapper/LoopMapper.java src/main/java/com/dbts/glyahhaigeneratecode/mapper/AppLoopMapper.java src/main/java/com/dbts/glyahhaigeneratecode/mapper/UserLoopApplyMapper.java
git commit -m "feat: loop DDL + 实体 + Mapper"
```

---

### Task 1.2: LoopWorkflowCompiler

**Files:**
- Create: `service/support/LoopWorkflowCompiler.java`
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/core/loop/LoopWorkflowCompilerTest.java`

- [ ] **Step 1: 写测试**

```java
// src/test/java/.../core/loop/LoopWorkflowCompilerTest.java
package com.dbts.glyahhaigeneratecode.core.loop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.dbts.glyahhaigeneratecode.service.support.LoopWorkflowCompiler;

class LoopWorkflowCompilerTest {
    
    @Test
    void compile_shouldJoinNonEmptySteps() {
        String json = "{\"templateId\":\"standard_v1\",\"steps\":[" +
            "{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"你是专家\"}," +
            "{\"key\":\"context\",\"label\":\"背景上下文\",\"content\":\"\"}," +
            "{\"key\":\"constraints\",\"label\":\"约束与边界\",\"content\":\"简洁输出\"}" +
            "]}";
        String result = LoopWorkflowCompiler.compile(json);
        assertTrue(result.contains("## 角色设定"));
        assertTrue(result.contains("你是专家"));
        assertTrue(result.contains("## 约束与边界"));
        assertTrue(result.contains("简洁输出"));
        // 空 content 的步骤不输出
        assertFalse(result.contains("背景上下文"));
    }
    
    @Test
    void compile_shouldSkipEmptyContentSteps() {
        String json = "{\"templateId\":\"standard_v1\",\"steps\":[" +
            "{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"\"}" +
            "]}";
        String result = LoopWorkflowCompiler.compile(json);
        assertEquals("", result.trim());
    }
    
    @Test
    void compile_shouldHandleNullContent() {
        String json = "{\"templateId\":\"standard_v1\",\"steps\":[" +
            "{\"key\":\"role\",\"label\":\"角色设定\",\"content\":null}" +
            "]}";
        String result = LoopWorkflowCompiler.compile(json);
        assertEquals("", result.trim());
    }
    
    @Test
    void compile_shouldReturnEmptyForInvalidJson() {
        String result = LoopWorkflowCompiler.compile("not json");
        assertEquals("", result);
    }
    
    @Test
    void compile_shouldReturnEmptyForNullInput() {
        String result = LoopWorkflowCompiler.compile(null);
        assertEquals("", result);
    }
}
```

- [ ] **Step 2: 运行测试（期望失败）**

```bash
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code"
.\mvnw.cmd -q test -Dtest="LoopWorkflowCompilerTest"
```
Expected: BUILD FAILURE（类不存在）

- [ ] **Step 3: 写实现**

```java
// service/support/LoopWorkflowCompiler.java
package com.dbts.glyahhaigeneratecode.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * workflowJson → compiledPrompt 编译器。
 * 遍历 steps[].content，过滤空值后按模板顺序拼接 Markdown 块。
 */
@Slf4j
@Component
public class LoopWorkflowCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将 workflowJson 编译为注入用的纯文本。
     * @param workflowJson JSON 字符串，可为 null 或非法
     * @return 编译后文本，无匹配时返回空字符串
     */
    public static String compile(String workflowJson) {
        if (workflowJson == null || workflowJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(workflowJson);
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray()) {
                return "";
            }
            List<String> blocks = new ArrayList<>();
            for (JsonNode step : steps) {
                String label = getTextOrEmpty(step, "label");
                String content = getTextOrEmpty(step, "content");
                if (content.isBlank()) continue;
                blocks.add("## " + label + "\n" + content);
            }
            return String.join("\n\n", blocks);
        } catch (Exception e) {
            log.warn("LoopWorkflowCompiler compile failed for json: {}", workflowJson, e);
            return "";
        }
    }

    private static String getTextOrEmpty(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return f != null ? f.asText("") : "";
    }
}
```

- [ ] **Step 4: 运行测试（期望通过）**

```bash
.\mvnw.cmd -q test -Dtest="LoopWorkflowCompilerTest"
```
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/service/support/LoopWorkflowCompiler.java src/test/java/com/dbts/glyahhaigeneratecode/core/loop/LoopWorkflowCompilerTest.java
git commit -m "feat: LoopWorkflowCompiler + 单测"
```

---

### Task 1.3: 前端路由 + GlobalHeader + 页面骨架

**Files:**
- Modify: `ai-generate-code-frontend/src/router/index.ts`
- Modify: `ai-generate-code-frontend/src/components/GlobalHeader.vue`
- Create: `ai-generate-code-frontend/src/page/Loop/LoopMarketView.vue`
- Create: `ai-generate-code-frontend/src/page/Loop/MyLoopView.vue`
- Create: `ai-generate-code-frontend/src/page/Loop/LoopCreateView.vue`

- [ ] **Step 1: 注册路由**

在 `router/index.ts` 新增：

```typescript
import LoopMarketView from '@/page/Loop/LoopMarketView.vue'
import MyLoopView from '@/page/Loop/MyLoopView.vue'
import LoopCreateView from '@/page/Loop/LoopCreateView.vue'
import LoopEditView from '@/page/Loop/LoopEditView.vue'

// 在路由表末尾添加：
  {
    path: '/loop',
    name: 'loop-market',
    component: LoopMarketView,
  },
  {
    path: '/user/loops',
    name: 'my-loop',
    component: MyLoopView,
  },
  {
    path: '/loop/create',
    name: 'loop-create',
    component: LoopCreateView,
  },
  {
    path: '/loop/:id/edit',
    name: 'loop-edit',
    component: LoopEditView,
    props: true,
  },
```

- [ ] **Step 2: GlobalHeader 添加导航**

在 `baseMenuItems` 中「代码生成」后插入 Loop：
```typescript
// GlobalHeader.vue computed baseMenuItems 数组
// 在 CODE_GENERATE 项后插入：
{
  key: 'loop',
  path: '/loop',
  label: 'Loop',
},
```

在用户下拉菜单「个人设置」后插入「我的 Loop」：
```typescript
// userDropdownItems 数组
// 在个人设置后插入：
{
  key: 'my-loop',
  icon: 'UserOutlined',
  label: '我的 Loop',
  path: '/user/loops',
},
```

- [ ] **Step 3: 写页面占位骨架**

```vue
<!-- src/page/Loop/LoopMarketView.vue -->
<template>
  <div class="loop-market">
    <h1>Loop 市场</h1>
    <p>发现精选与公开 Loop</p>
  </div>
</template>
```

```vue
<!-- src/page/Loop/MyLoopView.vue -->
<template>
  <div class="my-loop">
    <h1>我的 Loop</h1>
    <p>管理我的技能集合</p>
  </div>
</template>
```

```vue
<!-- src/page/Loop/LoopCreateView.vue -->
<template>
  <div class="loop-create">
    <h1>创建 Loop</h1>
    <p>5 步模板创作</p>
  </div>
</template>
```

```vue
<!-- src/page/Loop/LoopEditView.vue -->
<template>
  <div class="loop-edit">
    <h1>编辑 Loop</h1>
  </div>
</template>
```

- [ ] **Step 4: 验证前端编译**

```bash
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend"
npm run build 2>&1 | tail -5
```
Expected: BUILD SUCCESS（或类型检查警告但不阻止）

- [ ] **Step 5: 提交**

```bash
git add ai-generate-code-frontend/src/router/index.ts ai-generate-code-frontend/src/components/GlobalHeader.vue ai-generate-code-frontend/src/page/Loop/
git commit -m "feat: 前端 Loop 路由 + 导航 + 页面骨架"
```

---

## Batch 2：Loop CRUD + AppLoop 库

### Task 2.1: LoopController + LoopService（CRUD + 导入 + 精选 + 审批 API）

**Files:**
- Create: `model/DTO/LoopAddRequest.java`
- Create: `model/DTO/LoopUpdateRequest.java`
- Create: `model/DTO/LoopQueryRequest.java`
- Create: `model/VO/LoopVO.java`
- Create: `service/LoopService.java`
- Create: `service/impl/LoopServiceImpl.java`
- Create: `controller/LoopController.java`
- (引用) `mapper/LoopMapper.java`（Task 1.1 已建）
- (引用) `service/support/LoopWorkflowCompiler.java`（Task 1.2 已建）

- [ ] **Step 1: DTO/VO**

```java
// model/DTO/LoopQueryRequest extends PageRequest
package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class LoopQueryRequest extends PageRequest {
    private String searchText; // 模糊匹配 loopName + description
}
```

```java
// model/DTO/LoopAddRequest.java
package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

@Data
public class LoopAddRequest {
    private String loopName;          // 必填，1-128
    private String description;       // 选填，最长512
    private String cover;
    private String workflowJson;      // 必填，JSON格式合法
    private String sourceType;        // created/imported
    private String visibility;        // public/private
}
```

```java
// model/DTO/LoopUpdateRequest.java
package com.dbts.glyahhaigeneratecode.model.DTO;

import lombok.Data;

@Data
public class LoopUpdateRequest {
    private Long id;                  // 必填
    private String loopName;
    private String description;
    private String cover;
    private String workflowJson;      // 非空时触发重编译
    private String visibility;
}
```

```java
// model/VO/LoopVO.java
package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class LoopVO {
    private Long id;
    private String loopName;
    private String description;
    private String cover;
    private Long userId;
    private Integer priority;
    private String workflowJson;
    private String compiledPrompt;
    private String sourceType;
    private String visibility;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 2: service 接口**

```java
// service/LoopService.java
package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopQueryRequest;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;
import com.dbts.glyahhaigeneratecode.common.PageRequest;
import java.util.List;

public interface LoopService {
    Long addLoop(LoopAddRequest req, Long userId);
    void updateLoop(LoopUpdateRequest req, Long userId);
    void deleteLoop(Long id, Long userId);
    LoopVO getLoopVO(Long id);
    List<LoopVO> myListPage(LoopQueryRequest req, Long userId);
    List<LoopVO> goodListPage(LoopQueryRequest req);
    Long importLoop(String rawContent, Long userId);
    void applyGood(Long loopId, String reason, Long userId);
    List<LoopVO> adminListPage(LoopQueryRequest req);
    void adminUpdate(LoopUpdateRequest req);
}
```

- [ ] **Step 3: controller**

```java
// controller/LoopController.java
package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopAddRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopUpdateRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.LoopQueryRequest;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;
import com.dbts.glyahhaigeneratecode.service.LoopService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.annotation.MyRole;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/loop")
@RequiredArgsConstructor
public class LoopController {

    private final LoopService loopService;
    private final UserService userService;

    @PostMapping("/add")
    public BaseResponse<Long> add(@RequestBody LoopAddRequest req) {
        Long userId = userService.getUserInSession().getId();
        // 后端校验
        ThrowUtils.throwIf(req.getLoopName() == null || req.getLoopName().isBlank(),
            ErrorCode.PARAMS_ERROR, "Loop名称不能为空");
        ThrowUtils.throwIf(req.getLoopName().length() > 128,
            ErrorCode.PARAMS_ERROR, "Loop名称最长128字符");
        ThrowUtils.throwIf(req.getWorkflowJson() == null || req.getWorkflowJson().isBlank(),
            ErrorCode.PARAMS_ERROR, "workflowJson不能为空");
        Long id = loopService.addLoop(req, userId);
        return ResultUtils.success(id);
    }

    @PostMapping("/update")
    public BaseResponse<Void> update(@RequestBody LoopUpdateRequest req) {
        Long userId = userService.getUserInSession().getId();
        ThrowUtils.throwIf(req.getId() == null, ErrorCode.PARAMS_ERROR, "ID不能为空");
        loopService.updateLoop(req, userId);
        return ResultUtils.success(null);
    }

    @PostMapping("/delete")
    public BaseResponse<Void> delete(@RequestParam Long id) {
        Long userId = userService.getUserInSession().getId();
        loopService.deleteLoop(id, userId);
        return ResultUtils.success(null);
    }

    @GetMapping("/get/vo")
    public BaseResponse<LoopVO> getVO(@RequestParam Long id) {
        return ResultUtils.success(loopService.getLoopVO(id));
    }

    @PostMapping("/my/list/page/vo")
    public BaseResponse<List<LoopVO>> myListPage(@RequestBody LoopQueryRequest req) {
        Long userId = userService.getUserInSession().getId();
        return ResultUtils.success(loopService.myListPage(req, userId));
    }

    @PostMapping("/good/list/page/vo")
    public BaseResponse<List<LoopVO>> goodListPage(@RequestBody LoopQueryRequest req) {
        return ResultUtils.success(loopService.goodListPage(req));
    }

    @PostMapping("/import")
    public BaseResponse<Long> importLoop(@RequestBody String rawContent) {
        Long userId = userService.getUserInSession().getId();
        ThrowUtils.throwIf(rawContent == null || rawContent.isBlank(),
            ErrorCode.PARAMS_ERROR, "导入内容不能为空");
        Long id = loopService.importLoop(rawContent, userId);
        return ResultUtils.success(id);
    }

    @PostMapping("/apply")
    public BaseResponse<Void> apply(@RequestParam Long loopId, @RequestParam(required = false) String reason) {
        Long userId = userService.getUserInSession().getId();
        loopService.applyGood(loopId, reason, userId);
        return ResultUtils.success(null);
    }

    @PostMapping("/admin/list/page/vo")
    @MyRole("ADMIN")
    public BaseResponse<List<LoopVO>> adminListPage(@RequestBody LoopQueryRequest req) {
        return ResultUtils.success(loopService.adminListPage(req));
    }

    @PostMapping("/admin/update")
    @MyRole("ADMIN")
    public BaseResponse<Void> adminUpdate(@RequestBody LoopUpdateRequest req) {
        loopService.adminUpdate(req);
        return ResultUtils.success(null);
    }
}
```

- [ ] **Step 4: service 实现**

```java
// service/impl/LoopServiceImpl.java
package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.service.LoopService;
import com.dbts.glyahhaigeneratecode.service.support.LoopWorkflowCompiler;
import com.dbts.glyahhaigeneratecode.model.entity.Loop;
import com.dbts.glyahhaigeneratecode.model.entity.UserLoopApply;
import com.dbts.glyahhaigeneratecode.model.DTO.*;
import com.dbts.glyahhaigeneratecode.model.VO.LoopVO;
import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.UserLoopApplyMapper;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.common.ErrorCode;
import com.dbts.glyahhaigeneratecode.utils.CacheKeyUtils;
import com.dbts.glyahhaigeneratecode.utils.SnowflakeIdWorker;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.paginate.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoopServiceImpl implements LoopService {

    private final LoopMapper loopMapper;
    private final UserLoopApplyMapper userLoopApplyMapper;
    private final StringRedisTemplate redisTemplate;
    private final SnowflakeIdWorker snowflakeIdWorker;

    // ========== CRUD ==========

    @Override
    @Transactional
    public Long addLoop(LoopAddRequest req, Long userId) {
        validateWorkflowJson(req.getWorkflowJson());

        Loop loop = new Loop();
        loop.setId(snowflakeIdWorker.nextId());
        loop.setLoopName(req.getLoopName().trim());
        loop.setDescription(req.getDescription() != null ? req.getDescription().trim() : "");
        loop.setCover(req.getCover() != null ? req.getCover() : "");
        loop.setUserId(userId);
        loop.setPriority(0);
        loop.setWorkflowJson(req.getWorkflowJson());
        loop.setCompiledPrompt(LoopWorkflowCompiler.compile(req.getWorkflowJson()));
        loop.setSourceType(req.getSourceType() != null ? req.getSourceType() : "created");
        loop.setVisibility(req.getVisibility() != null ? req.getVisibility() : "private");
        loop.setIsDelete(0);
        loopMapper.insert(loop);

        // 写 Redis 缓存
        redisTemplate.opsForValue().set(
            "loop:compiled:" + loop.getId(),
            loop.getCompiledPrompt(),
            30 + (long)(Math.random() * 5),
            TimeUnit.MINUTES
        );
        return loop.getId();
    }

    @Override
    @Transactional
    @CacheEvict(value = "good_loop_page", allEntries = true)
    public void updateLoop(LoopUpdateRequest req, Long userId) {
        Loop loop = loopMapper.selectOneById(req.getId());
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
            ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        ThrowUtils.throwIf(!loop.getUserId().equals(userId),
            ErrorCode.FORBIDDEN_ERROR, "无权修改他人Loop");

        if (req.getLoopName() != null) loop.setLoopName(req.getLoopName().trim());
        if (req.getDescription() != null) loop.setDescription(req.getDescription().trim());
        if (req.getCover() != null) loop.setCover(req.getCover());
        if (req.getVisibility() != null) loop.setVisibility(req.getVisibility());
        if (req.getWorkflowJson() != null && !req.getWorkflowJson().isBlank()) {
            validateWorkflowJson(req.getWorkflowJson());
            loop.setWorkflowJson(req.getWorkflowJson());
            loop.setCompiledPrompt(LoopWorkflowCompiler.compile(req.getWorkflowJson()));
        }
        loopMapper.update(loop);

        // 删 Redis
        redisTemplate.delete("loop:compiled:" + loop.getId());
    }

    @Override
    @Transactional
    public void deleteLoop(Long id, Long userId) {
        Loop loop = loopMapper.selectOneById(id);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
            ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        ThrowUtils.throwIf(!loop.getUserId().equals(userId) && !isAdmin(userId),
            ErrorCode.FORBIDDEN_ERROR, "无权删除");

        loop.setIsDelete(1);
        loopMapper.update(loop);

        // 删 Redis：compiled + 反向索引
        redisTemplate.delete("loop:compiled:" + id);
        Set<String> appIds = redisTemplate.opsForSet().members("loop:app_ids:" + id);
        if (appIds != null && !appIds.isEmpty()) {
            String[] keys = appIds.stream()
                .map(aid -> "app:loop:ids:" + aid)
                .toArray(String[]::new);
            redisTemplate.delete(Arrays.asList(keys));
        }
        redisTemplate.delete("loop:app_ids:" + id);
    }

    @Override
    public LoopVO getLoopVO(Long id) {
        Loop loop = loopMapper.selectOneById(id);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
            ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        return toVO(loop);
    }

    // ========== 分页 ==========

    @Override
    public List<LoopVO> myListPage(LoopQueryRequest req, Long userId) {
        QueryWrapper qw = new QueryWrapper()
            .eq("user_id", userId)
            .eq("is_delete", 0)
            .orderBy("create_time", false);
        if (req.getSearchText() != null && !req.getSearchText().isBlank()) {
            qw.and(w -> w.like("loop_name", req.getSearchText())
                .or().like("description", req.getSearchText()));
        }
        Page<Loop> page = loopMapper.paginate(req.getPageCurrent(), req.getPageSize(), qw);
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "good_loop_page", key = "#req.toString()")
    public List<LoopVO> goodListPage(LoopQueryRequest req) {
        QueryWrapper qw = new QueryWrapper()
            .ge("priority", 99)
            .eq("is_delete", 0)
            .eq("visibility", "public")
            .orderBy("priority", false)
            .orderBy("create_time", false);
        if (req.getSearchText() != null && !req.getSearchText().isBlank()) {
            qw.and(w -> w.like("loop_name", req.getSearchText())
                .or().like("description", req.getSearchText()));
        }
        Page<Loop> page = loopMapper.paginate(req.getPageCurrent(), req.getPageSize(), qw);
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    // ========== 导入 ==========

    @Override
    @Transactional
    public Long importLoop(String rawContent, Long userId) {
        // 解析 frontmatter + body
        // 格式: ---\nkey: value\n---\n## 标题\nbody
        String loopName = "未命名技能";
        String description = "";
        String visibility = "public";
        StringBuilder bodyBuilder = new StringBuilder();

        try {
            if (rawContent.startsWith("---")) {
                int endFront = rawContent.indexOf("---", 3);
                if (endFront > 0) {
                    String front = rawContent.substring(3, endFront).trim();
                    for (String line : front.split("\n")) {
                        line = line.trim();
                        if (line.startsWith("name:")) loopName = line.substring(5).trim();
                        else if (line.startsWith("description:")) description = line.substring(12).trim();
                        else if (line.startsWith("visibility:")) visibility = line.substring(11).trim();
                    }
                    int bodyStart = endFront + 3;
                    if (bodyStart < rawContent.length()) {
                        bodyBuilder.append(rawContent.substring(bodyStart).trim());
                    }
                }
            } else {
                bodyBuilder.append(rawContent.trim());
            }
        } catch (Exception e) {
            throw new RuntimeException(ErrorCode.PARAMS_ERROR.getCode() + ":导入内容解析失败");
        }

        // 构建 workflowJson
        String body = bodyBuilder.toString().trim();
        String workflowJson = buildWorkflowJsonFromBody(body);

        LoopAddRequest req = new LoopAddRequest();
        req.setLoopName(loopName);
        req.setDescription(description);
        req.setWorkflowJson(workflowJson);
        req.setSourceType("imported");
        req.setVisibility(visibility);
        return addLoop(req, userId);
    }

    // ========== 申请精选 ==========

    @Override
    @Transactional
    public void applyGood(Long loopId, String reason, Long userId) {
        // 校验 Loop 属于该用户
        Loop loop = loopMapper.selectOneById(loopId);
        ThrowUtils.throwIf(loop == null || loop.getIsDelete() == 1,
            ErrorCode.NOT_FOUND_ERROR, "Loop不存在");
        ThrowUtils.throwIf(!loop.getUserId().equals(userId),
            ErrorCode.FORBIDDEN_ERROR, "只能申请自己创建的Loop");

        UserLoopApply apply = new UserLoopApply();
        apply.setId(snowflakeIdWorker.nextId());
        apply.setLoopId(loopId);
        apply.setUserId(userId);
        apply.setOperate(1);
        apply.setStatus(0);
        apply.setApplyReason(reason != null ? reason : "");
        userLoopApplyMapper.insert(apply);
    }

    // ========== 管理员 ==========

    @Override
    public List<LoopVO> adminListPage(LoopQueryRequest req) {
        QueryWrapper qw = new QueryWrapper().orderBy("create_time", false);
        if (req.getSearchText() != null && !req.getSearchText().isBlank()) {
            qw.and(w -> w.like("loop_name", req.getSearchText())
                .or().like("description", req.getSearchText()));
        }
        Page<Loop> page = loopMapper.paginate(req.getPageCurrent(), req.getPageSize(), qw);
        return page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    @CacheEvict(value = "good_loop_page", allEntries = true)
    public void adminUpdate(LoopUpdateRequest req) {
        ThrowUtils.throwIf(req.getId() == null, ErrorCode.PARAMS_ERROR, "ID不能为空");
        Loop loop = loopMapper.selectOneById(req.getId());
        ThrowUtils.throwIf(loop == null, ErrorCode.NOT_FOUND_ERROR, "Loop不存在");

        if (req.getLoopName() != null) loop.setLoopName(req.getLoopName().trim());
        if (req.getDescription() != null) loop.setDescription(req.getDescription().trim());
        if (req.getVisibility() != null) loop.setVisibility(req.getVisibility());
        // 管理员可以调 priority
        if (req.getPriority() != null) loop.setPriority(req.getPriority());
        if (req.getWorkflowJson() != null && !req.getWorkflowJson().isBlank()) {
            validateWorkflowJson(req.getWorkflowJson());
            loop.setWorkflowJson(req.getWorkflowJson());
            loop.setCompiledPrompt(LoopWorkflowCompiler.compile(req.getWorkflowJson()));
        }
        loopMapper.update(loop);
        redisTemplate.delete("loop:compiled:" + loop.getId());
    }

    // ========== 工具方法 ==========

    private LoopVO toVO(Loop loop) {
        LoopVO vo = new LoopVO();
        vo.setId(loop.getId());
        vo.setLoopName(loop.getLoopName());
        vo.setDescription(loop.getDescription());
        vo.setCover(loop.getCover());
        vo.setUserId(loop.getUserId());
        vo.setPriority(loop.getPriority());
        vo.setWorkflowJson(loop.getWorkflowJson());
        vo.setCompiledPrompt(loop.getCompiledPrompt());
        vo.setSourceType(loop.getSourceType());
        vo.setVisibility(loop.getVisibility());
        vo.setCreateTime(loop.getCreateTime());
        vo.setUpdateTime(loop.getUpdateTime());
        return vo;
    }

    private void validateWorkflowJson(String json) {
        ThrowUtils.throwIf(json == null || json.isBlank(),
            ErrorCode.PARAMS_ERROR, "workflowJson不能为空");
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode steps = root.get("steps");
            ThrowUtils.throwIf(steps == null || !steps.isArray() || steps.size() == 0,
                ErrorCode.PARAMS_ERROR, "workflowJson.steps至少1项");
            for (com.fasterxml.jackson.databind.JsonNode step : steps) {
                ThrowUtils.throwIf(step.get("key") == null || step.get("key").asText("").isBlank(),
                    ErrorCode.PARAMS_ERROR, "steps每项key不能为空");
            }
        } catch (Exception e) {
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "workflowJson格式不合法");
        }
    }

    private String buildWorkflowJsonFromBody(String body) {
        // 按 ## 标题分割 body，映射到标准 steps
        // 简化实现：所有内容放入 step 0（role），其余留空
        if (body.isBlank()) {
            return "{\"templateId\":\"standard_v1\",\"steps\":[{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"\"}]}";
        }
        // 尝试按 Markdown 标题拆分
        List<Map<String, String>> stepsList = new ArrayList<>();
        String[] lines = body.split("\n");
        String currentLabel = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (currentLabel != null) {
                    Map<String, String> step = new HashMap<>();
                    step.put("key", mapLabelToKey(currentLabel));
                    step.put("content", currentContent.toString().trim());
                    stepsList.add(step);
                }
                currentLabel = line.substring(3).trim();
                currentContent = new StringBuilder();
            } else {
                if (currentContent.length() > 0) currentContent.append("\n");
                currentContent.append(line);
            }
        }
        if (currentLabel != null) {
            Map<String, String> step = new HashMap<>();
            step.put("key", mapLabelToKey(currentLabel));
            step.put("content", currentContent.toString().trim());
            stepsList.add(step);
        }

        try {
            Map<String, Object> root = new HashMap<>();
            root.put("templateId", "standard_v1");
            root.put("steps", stepsList);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(root);
        } catch (Exception e) {
            return "{\"templateId\":\"standard_v1\",\"steps\":[{\"key\":\"role\",\"label\":\"角色设定\",\"content\":\"\"}]}";
        }
    }

    private String mapLabelToKey(String label) {
        if (label.contains("角色")) return "role";
        if (label.contains("背景") || label.contains("上下文")) return "context";
        if (label.contains("约束") || label.contains("边界")) return "constraints";
        if (label.contains("步骤") || label.contains("工作流") || label.contains("执行")) return "workflow";
        if (label.contains("输出") || label.contains("格式")) return "output";
        return "role";
    }

    private boolean isAdmin(Long userId) {
        // 从 userService 读取角色 或简化为 role check
        return false; // 由 @MyRole AOP 处理
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
.\mvnw.cmd -q -DskipTests compile
```
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/LoopAddRequest.java src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/LoopUpdateRequest.java src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/LoopQueryRequest.java src/main/java/com/dbts/glyahhaigeneratecode/model/VO/LoopVO.java src/main/java/com/dbts/glyahhaigeneratecode/service/LoopService.java src/main/java/com/dbts/glyahhaigeneratecode/service/impl/LoopServiceImpl.java src/main/java/com/dbts/glyahhaigeneratecode/controller/LoopController.java
git commit -m "feat: Loop CRUD 服务 + 控制器"
```

---

### Task 2.2: AppLoopService + AppLoopController + AppAddRequest 改造

**Files:**
- Create: `service/AppLoopService.java`
- Create: `service/impl/AppLoopServiceImpl.java`
- Create: `controller/AppLoopController.java`
- Modify: `model/DTO/AppAddRequest.java`
- Modify: `service/impl/AppServiceImpl.java`

- [ ] **Step 1: AppLoopService 接口**

```java
// service/AppLoopService.java
package com.dbts.glyahhaigeneratecode.service;

import java.util.List;
import java.util.Map;

public interface AppLoopService {
    void bindLoops(Long appId, List<Long> loopIds, String addedFrom);
    void addLoop(Long appId, Long loopId, String addedFrom);
    List<Map<String, Object>> listLoopVOs(Long appId);
    void removeLoop(Long appId, Long loopId);
}
```

- [ ] **Step 2: AppLoopService 实现**

```java
// service/impl/AppLoopServiceImpl.java
package com.dbts.glyahhaigeneratecode.service.impl;

import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import com.dbts.glyahhaigeneratecode.model.entity.AppLoop;
import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppLoopServiceImpl implements AppLoopService {

    private final AppLoopMapper appLoopMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public void bindLoops(Long appId, List<Long> loopIds, String addedFrom) {
        if (loopIds == null || loopIds.isEmpty()) return;
        for (Long loopId : loopIds) {
            AppLoop al = new AppLoop();
            al.setAppId(appId);
            al.setLoopId(loopId);
            al.setAddedFrom(addedFrom);
            appLoopMapper.insert(al);
            // 写反向索引
            redisTemplate.opsForSet().add("loop:app_ids:" + loopId, String.valueOf(appId));
        }
        redisTemplate.delete("app:loop:ids:" + appId);
    }

    @Override
    @Transactional
    public void addLoop(Long appId, Long loopId, String addedFrom) {
        AppLoop al = new AppLoop();
        al.setAppId(appId);
        al.setLoopId(loopId);
        al.setAddedFrom(addedFrom);
        // 唯一索引防重复，insert ignore 或 try-catch
        try {
            appLoopMapper.insert(al);
            redisTemplate.opsForSet().add("loop:app_ids:" + loopId, String.valueOf(appId));
        } catch (Exception e) {
            log.warn("addLoop duplicate ignored: appId={} loopId={}", appId, loopId);
        }
        redisTemplate.delete("app:loop:ids:" + appId);
    }

    @Override
    public List<Map<String, Object>> listLoopVOs(Long appId) {
        // 先从 Redis 读缓存
        String cacheKey = "app:loop:ids:" + appId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !cached.equals("{}")) {
            // 有缓存直接返回（简化：缓存存的是完整 vo 列表 JSON，或只存 ids）
        }

        // 读 MySQL
        List<AppLoop> als = appLoopMapper.selectListByQuery(
            new com.mybatisflex.core.query.QueryWrapper().eq("app_id", appId)
        );
        if (als.isEmpty()) return Collections.emptyList();

        List<Long> loopIds = als.stream().map(AppLoop::getLoopId).collect(Collectors.toList());

        // 批量查 loop 表
        List<com.dbts.glyahhaigeneratecode.model.entity.Loop> loops =
            new com.dbts.glyahhaigeneratecode.mapper.LoopMapper() {} // 这里需要注入 loopMapper
                .selectListByQuery(
                    new com.mybatisflex.core.query.QueryWrapper().in("id", loopIds).eq("is_delete", 0)
                );

        List<Map<String, Object>> result = new ArrayList<>();
        for (com.dbts.glyahhaigeneratecode.model.entity.Loop l : loops) {
            Map<String, Object> item = new HashMap<>();
            item.put("loopId", l.getId());
            item.put("loopName", l.getLoopName());
            item.put("description", l.getDescription());
            result.add(item);
        }

        // 写缓存
        // 简化：只缓存 loopIds 列表，1h TTL
        String idsJson = loopIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (!idsJson.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, "[" + idsJson + "]", 1, TimeUnit.HOURS);
        }

        return result;
    }

    @Override
    @Transactional
    public void removeLoop(Long appId, Long loopId) {
        appLoopMapper.deleteByQuery(
            new com.mybatisflex.core.query.QueryWrapper()
                .eq("app_id", appId).eq("loop_id", loopId)
        );
        redisTemplate.opsForSet().remove("loop:app_ids:" + loopId, String.valueOf(appId));
        redisTemplate.delete("app:loop:ids:" + appId);
    }
}
```

> 注意：`listLoopVOs` 中需要注入 `LoopMapper`，重构 `AppLoopServiceImpl` 为：

```java
private final LoopMapper loopMapper; // 追加字段
```

- [ ] **Step 3: AppLoopController**

```java
// controller/AppLoopController.java
package com.dbts.glyahhaigeneratecode.controller;

import com.dbts.glyahhaigeneratecode.common.BaseResponse;
import com.dbts.glyahhaigeneratecode.common.ResultUtils;
import com.dbts.glyahhaigeneratecode.service.AppLoopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/app/loop")
@RequiredArgsConstructor
public class AppLoopController {

    private final AppLoopService appLoopService;

    @PostMapping("/bind")
    public BaseResponse<Void> bind(@RequestParam Long appId, @RequestBody List<Long> loopIds) {
        appLoopService.bindLoops(appId, loopIds, "creation");
        return ResultUtils.success(null);
    }

    @PostMapping("/add")
    public BaseResponse<Void> add(@RequestParam Long appId, @RequestParam Long loopId) {
        appLoopService.addLoop(appId, loopId, "market");
        return ResultUtils.success(null);
    }

    @PostMapping("/list/vo")
    public BaseResponse<List<Map<String, Object>>> listVO(@RequestParam Long appId) {
        return ResultUtils.success(appLoopService.listLoopVOs(appId));
    }

    @PostMapping("/remove")
    public BaseResponse<Void> remove(@RequestParam Long appId, @RequestParam Long loopId) {
        appLoopService.removeLoop(appId, loopId);
        return ResultUtils.success(null);
    }
}
```

- [ ] **Step 4: 修改 AppAddRequest**

追加字段：
```java
// AppAddRequest.java 已有结构后追加：
private List<Long> loopIds;
```

- [ ] **Step 5: 修改 AppServiceImpl.createApp**

找到 `createApp` 方法，在 `appMapper.insert(app)` 成功后追加：
```java
if (addRequest.getLoopIds() != null && !addRequest.getLoopIds().isEmpty()) {
    appLoopService.bindLoops(app.getId(), addRequest.getLoopIds(), "creation");
}
```

需要在类中注入 `AppLoopService`。

- [ ] **Step 6: 编译验证**

```bash
.\mvnw.cmd -q -DskipTests compile
```
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/service/AppLoopService.java src/main/java/com/dbts/glyahhaigeneratecode/service/impl/AppLoopServiceImpl.java src/main/java/com/dbts/glyahhaigeneratecode/controller/AppLoopController.java src/main/java/com/dbts/glyahhaigeneratecode/model/DTO/AppAddRequest.java src/main/java/com/dbts/glyahhaigeneratecode/service/impl/AppServiceImpl.java
git commit -m "feat: AppLoop 库 + createApp 内联绑定"
```

---

## Batch 3：SSE 注入 + 前端核心页面

### Task 3.1: LoopInjectService + ChatToGenCodeImpl + Controller loopId + Redis

**Files:**
- Create: `service/support/LoopInjectService.java`
- Modify: `controller/ChatToGenCodeController.java`
- Modify: `service/impl/ChatToGenCodeImpl.java`

- [ ] **Step 1: LoopInjectService**

```java
// service/support/LoopInjectService.java
package com.dbts.glyahhaigeneratecode.service.support;

import com.dbts.glyahhaigeneratecode.mapper.LoopMapper;
import com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper;
import com.dbts.glyahhaigeneratecode.model.entity.Loop;
import com.dbts.glyahhaigeneratecode.model.entity.AppLoop;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Loop 注入服务。
 * 校验 loopId 归属后在 userMessage 后缀拼接 [loop_skill] 块。
 * 优先读 Redis 缓存，miss 则回填 MySQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoopInjectService {

    private final LoopMapper loopMapper;
    private final AppLoopMapper appLoopMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 如果 loopId 合法且属于应用库，则后缀注入 tagged 块。
     * loopId 为空/无效/不属于本应用 → 跳过注入，不报错。
     */
    public String injectIfPresent(String message, Long userId, Long appId, Long loopId) {
        if (loopId == null) return message;
        if (!validateLoop(userId, appId, loopId)) return message;

        String compiled = getCompiledPrompt(loopId);
        if (compiled == null || compiled.isBlank()) return message;

        String loopName = getLoopName(loopId);
        String block = "\n[loop_skill name=\"" + escape(loopName) + "\"]\n"
            + compiled + "\n[/loop_skill]";
        return message + block;
    }

    /**
     * 校验 loopId 是否属于 app 的 app_loop 库，或是用户自有 Loop。
     */
    private boolean validateLoop(Long userId, Long appId, Long loopId) {
        // 先查 app_loop
        long count = appLoopMapper.selectCountByQuery(
            new QueryWrapper().eq("app_id", appId).eq("loop_id", loopId)
        );
        if (count > 0) return true;
        // 再查用户的 Loop
        count = loopMapper.selectCountByQuery(
            new QueryWrapper().eq("id", loopId).eq("user_id", userId).eq("is_delete", 0)
        );
        return count > 0;
    }

    /**
     * 读 Redis → miss 读 MySQL → 回填 Redis。
     */
    private String getCompiledPrompt(Long loopId) {
        String cacheKey = "loop:compiled:" + loopId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if ("{}".equals(cached)) return null; // 空值占位
            return cached;
        }
        Loop loop = loopMapper.selectOneById(loopId);
        if (loop == null || loop.getIsDelete() == 1 || loop.getCompiledPrompt() == null) {
            // 空值占位防穿透
            redisTemplate.opsForValue().set(cacheKey, "{}", 60, TimeUnit.SECONDS);
            return null;
        }
        String compiled = loop.getCompiledPrompt();
        redisTemplate.opsForValue().set(
            cacheKey, compiled,
            30 + (long)(Math.random() * 5), TimeUnit.MINUTES
        );
        return compiled;
    }

    private String getLoopName(Long loopId) {
        String cacheKey = "loop:compiled:" + loopId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !"{}".equals(cached)) {
            // 从缓存取名字不靠谱，直接查一次 MySQL
        }
        Loop loop = loopMapper.selectOneById(loopId);
        return loop != null ? loop.getLoopName() : "";
    }

    private String escape(String s) {
        return s != null ? s.replace("\"", "&quot;").replace("\n", " ") : "";
    }
}
```

- [ ] **Step 2: Controller 加 loopId 参数**

在 `ChatToGenCodeController.java` 的 `/gen/code` 端点方法签名加：
```java
@RequestParam(required = false) Long loopId
```
在构建请求对象或调用 facade 时透传。

- [ ] **Step 3: ChatToGenCodeImpl 接入注入**

找到 `sendMessage` 或对应入口方法，在 `injectPersonalizationPrompt` 调用之后追加：
```java
// 注入顺序：personalization → message → loop_skill（后缀拼接）
String enhanced = injectPersonalizationPrompt(message, userId);
enhanced = loopInjectService.injectIfPresent(enhanced, userId, appId, loopId);
```

需要注入 `LoopInjectService` 并在类中新增 `loopId` 参数接收。

- [ ] **Step 4: 写注入单测**

```java
// src/test/java/.../service/support/LoopInjectServiceTest.java
package com.dbts.glyahhaigeneratecode.service.support;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoopInjectServiceTest {

    @Mock
    private com.dbts.glyahhaigeneratecode.mapper.LoopMapper loopMapper;
    @Mock
    private com.dbts.glyahhaigeneratecode.mapper.AppLoopMapper appLoopMapper;
    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @InjectMocks
    private LoopInjectService injectService;

    @Test
    void injectIfPresent_nullLoopId_returnsOriginal() {
        String result = injectService.injectIfPresent("hello", 1L, 1L, null);
        assertEquals("hello", result);
    }

    @Test
    void injectIfPresent_invalidLoop_returnsOriginal() {
        Mockito.when(appLoopMapper.selectCountByQuery(Mockito.any()))
            .thenReturn(0L);
        Mockito.when(loopMapper.selectCountByQuery(Mockito.any()))
            .thenReturn(0L);
        String result = injectService.injectIfPresent("hello", 1L, 1L, 999L);
        assertEquals("hello", result);
    }
}
```

- [ ] **Step 5: 编译 + 测试**

```bash
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd test -Dtest="LoopInjectServiceTest"
```
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/service/support/LoopInjectService.java src/main/java/com/dbts/glyahhaigeneratecode/controller/ChatToGenCodeController.java src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatToGenCodeImpl.java src/test/java/com/dbts/glyahhaigeneratecode/service/support/LoopInjectServiceTest.java
git commit -m "feat: LoopInjectService + SSE loopId + 单测"
```

---

## Batch 4：前端完善

### Task 4.1: LoopMarketView + MyLoopView 完整实现

**Files:**
- Modify: `ai-generate-code-frontend/src/page/Loop/LoopMarketView.vue`
- Modify: `ai-generate-code-frontend/src/page/Loop/MyLoopView.vue`

- [ ] **Step 1: LoopMarketView 完整实现**

```vue
<template>
  <div class="loop-market">
    <a-tabs>
      <a-tab-pane key="good" tab="精选 Loop">
        <div class="loop-grid">
          <a-card v-for="loop in goodList" :key="loop.id" class="loop-card"
            :hoverable="true">
            <template #cover>
              <div class="loop-card-icon"><svg><!-- SVG 图标占位 --></svg></div>
            </template>
            <a-card-meta :title="loop.loopName" :description="loop.description" />
            <template #actions>
              <a-button type="link" @click="addToApp(loop.id)">加入我的应用</a-button>
              <a-button type="link" @click="viewDetail(loop.id)">详情</a-button>
            </template>
          </a-card>
        </div>
      </a-tab-pane>
      <a-tab-pane key="explore" tab="探索">
        <!-- 同精选布局，加载 public 分页 -->
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { loopController } from '@/api/loopController' // openapi2ts 生成后

const goodList = ref([])
const loadGood = async () => {
  // goodListPage API 调用
}
onMounted(loadGood)
</script>

<style scoped>
.loop-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
.loop-card { transition: box-shadow 0.3s; }
.loop-card:hover { box-shadow: 0 0 12px var(--primary-color); }
.loop-card-icon { height: 120px; display: flex; align-items: center; justify-content: center; background: var(--bg-card); }
</style>
```

- [ ] **Step 2: MyLoopView 完整实现**

```vue
<template>
  <div class="my-loop">
    <div class="my-loop-header">
      <h1>我的 Loop</h1>
      <div class="my-loop-actions">
        <a-button type="primary" @click="$router.push('/loop/create')">创建 Loop</a-button>
        <a-button @click="showImport = true">导入 Skill</a-button>
      </div>
    </div>
    <div class="loop-grid">
      <a-card v-for="loop in myList" :key="loop.id" class="loop-card" hoverable
        @click="$router.push(`/loop/${loop.id}/edit`)">
        <a-card-meta :title="loop.loopName" :description="loop.description" />
        <div class="loop-card-meta">
          <a-tag>{{ loop.sourceType }}</a-tag>
          <a-tag :color="loop.visibility === 'public' ? 'green' : 'default'">{{ loop.visibility }}</a-tag>
        </div>
        <template #actions>
          <a-button v-if="loop.priority < 99" type="link" @click.stop="applyGood(loop.id)">申请精选</a-button>
          <a-tag v-else color="gold">精选</a-tag>
        </template>
      </a-card>
      <a-card v-if="myList.length === 0" class="loop-card-empty">
        <a-empty description="暂无 Loop，点击上方创建或导入">
          <a-button type="primary" @click="$router.push('/loop/create')">创建第一个 Loop</a-button>
        </a-empty>
      </a-card>
    </div>
    <!-- 导入弹窗 -->
    <a-modal v-model:visible="showImport" title="导入 Skill" @ok="handleImport">
      <a-textarea v-model:value="importContent" rows="10" placeholder="粘贴 .md 格式内容..." />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

const myList = ref([])
const showImport = ref(false)
const importContent = ref('')

const loadMyList = async () => {
  // myListPage API
}
const applyGood = async (id: number) => {
  // apply API
}
const handleImport = async () => {
  // import API
}
onMounted(loadMyList)
</script>

<style scoped>
.my-loop-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
.my-loop-actions { display: flex; gap: 8px; }
.loop-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; }
.loop-card-meta { margin-top: 12px; display: flex; gap: 8px; }
.loop-card-empty { display: flex; align-items: center; justify-content: center; min-height: 200px; }
</style>
```

- [ ] **Step 3: 提交**

```bash
git add ai-generate-code-frontend/src/page/Loop/LoopMarketView.vue ai-generate-code-frontend/src/page/Loop/MyLoopView.vue
git commit -m "feat: LoopMarketView + MyLoopView 完整实现"
```

---

### Task 4.2: LoopCreateView 完整（5步模板 + 导入 + 自动解析）

**Files:**
- Modify: `ai-generate-code-frontend/src/page/Loop/LoopCreateView.vue`

- [ ] **Step 1: 模板创作页完整实现**

```vue
<template>
  <div class="loop-create">
    <a-row :gutter="24">
      <!-- 左侧：5 步流程图 -->
      <a-col :xs="24" :md="8">
        <div class="flow-diagram">
          <div v-for="(step, idx) in templateSteps" :key="step.key"
            class="flow-node" :class="{ active: currentStep === idx }"
            @click="currentStep = idx">
            <div class="flow-node-index">{{ idx + 1 }}</div>
            <div class="flow-node-label">{{ step.label }}</div>
            <div v-if="idx < templateSteps.length - 1" class="flow-connector" />
          </div>
        </div>
        <!-- 导入区 -->
        <a-divider>导入</a-divider>
        <a-textarea v-model:value="importRaw" rows="6" placeholder="粘贴以下格式自动解析...

---
name: 我的技能
description: 一个示例技能
visibility: public
---
## 角色设定
你是一个XX专家

## 约束与边界
- 输出简洁

## 执行步骤
1. 分析需求
2. 生成代码" />
        <a-button type="dashed" block @click="parseImport" style="margin-top: 8px">
          解析导入
        </a-button>
      </a-col>
      <!-- 右侧：编辑区 -->
      <a-col :xs="24" :md="16">
        <a-form layout="vertical">
          <a-form-item label="Loop 名称" required>
            <a-input v-model:value="form.loopName" maxlength="128" placeholder="给技能起个名字" />
          </a-form-item>
          <a-form-item label="简介">
            <a-textarea v-model:value="form.description" :rows="2" maxlength="512" />
          </a-form-item>
          <a-divider />
          <div v-for="(step, idx) in form.steps" :key="step.key">
            <a-form-item v-if="idx === currentStep" :label="step.label">
              <a-textarea v-model:value="step.content" :rows="6" :placeholder="step.placeholder || ''" />
            </a-form-item>
          </div>
          <a-form-item label="可见性">
            <a-radio-group v-model:value="form.visibility">
              <a-radio value="public">公开</a-radio>
              <a-radio value="private">私有</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item>
            <a-button type="primary" @click="handleSave" :loading="saving">保存</a-button>
          </a-form-item>
        </a-form>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const editId = route.params.id as string | undefined
const saving = ref(false)
const currentStep = ref(0)
const importRaw = ref('')

const templateSteps = [
  { key: 'role', label: '角色设定', placeholder: '你扮演…' },
  { key: 'context', label: '背景上下文', placeholder: '补充背景信息' },
  { key: 'constraints', label: '约束与边界', placeholder: '行为限制、输出规范' },
  { key: 'workflow', label: '执行步骤', placeholder: '1. 分析需求\n2. 生成代码' },
  { key: 'output', label: '输出格式', placeholder: '返回 Markdown 格式结果' },
]

const form = reactive({
  loopName: '',
  description: '',
  visibility: 'private',
  steps: templateSteps.map(s => ({ key: s.key, label: s.label, content: '', placeholder: s.placeholder })),
})

// 解析导入
const parseImport = () => {
  const raw = importRaw.value.trim()
  if (!raw) return
  try {
    let name = '未命名技能'
    let desc = ''
    let vis = 'private'
    let body = raw
    if (raw.startsWith('---')) {
      const endIdx = raw.indexOf('---', 3)
      if (endIdx > 0) {
        const front = raw.substring(3, endIdx).trim()
        front.split('\n').forEach(line => {
          if (line.startsWith('name:')) name = line.substring(5).trim()
          else if (line.startsWith('description:')) desc = line.substring(12).trim()
          else if (line.startsWith('visibility:')) vis = line.substring(11).trim()
        })
        body = raw.substring(endIdx + 3).trim()
      }
    }
    form.loopName = name
    form.description = desc
    form.visibility = vis

    // 按 ## 标题拆分 body 填入步骤
    const stepLines = body.split('\n')
    let currentKey = ''
    const contentMap: Record<string, string> = {}
    for (const line of stepLines) {
      if (line.startsWith('## ')) {
        currentKey = mapLabelToKey(line.substring(3).trim())
        if (!contentMap[currentKey]) contentMap[currentKey] = ''
      } else if (currentKey) {
        contentMap[currentKey] = (contentMap[currentKey] || '') + line + '\n'
      }
    }
    form.steps.forEach(s => {
      if (contentMap[s.key]) s.content = contentMap[s.key].trim()
    })
  } catch (e) {
    console.error('解析失败', e)
  }
}

const mapLabelToKey = (label: string): string => {
  if (label.includes('角色')) return 'role'
  if (label.includes('背景') || label.includes('上下文')) return 'context'
  if (label.includes('约束') || label.includes('边界')) return 'constraints'
  if (label.includes('步骤') || label.includes('执行') || label.includes('工作流')) return 'workflow'
  if (label.includes('输出') || label.includes('格式')) return 'output'
  return 'role'
}

// 编译 workflowJson
const buildWorkflowJson = () => {
  const steps = form.steps
    .filter(s => s.content.trim())
    .map(s => ({ key: s.key, label: s.label, content: s.content.trim() }))
  return {
    templateId: 'standard_v1',
    steps,
  }
}

const handleSave = async () => {
  if (!form.loopName.trim()) return
  saving.value = true
  try {
    const payload = {
      loopName: form.loopName.trim(),
      description: form.description.trim(),
      visibility: form.visibility,
      workflowJson: JSON.stringify(buildWorkflowJson()),
    }
    // 调 API: add 或 update
    console.log('保存', editId ? '更新' : '创建', payload)
    router.push('/user/loops')
  } finally {
    saving.value = false
  }
}

// 编辑模式加载
onMounted(async () => {
  if (editId) {
    // 调 get/vo 回填 form
  }
})
</script>

<style scoped>
.flow-diagram { padding: 24px; }
.flow-node { display: flex; align-items: center; gap: 12px; padding: 12px; cursor: pointer; border-radius: 8px; position: relative; }
.flow-node:hover { background: var(--bg-card); }
.flow-node.active { background: var(--primary-1); border: 1px solid var(--primary-color); }
.flow-node-index { width: 28px; height: 28px; border-radius: 50%; background: var(--primary-color); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: bold; }
.flow-node.active .flow-node-index { background: var(--primary-7); }
.flow-node-label { font-size: 14px; }
.flow-connector { width: 2px; height: 24px; background: var(--border-color); margin: 0 0 0 13px; }
</style>
```

- [ ] **Step 2: 提交**

```bash
git add ai-generate-code-frontend/src/page/Loop/LoopCreateView.vue ai-generate-code-frontend/src/page/Loop/LoopEditView.vue
git commit -m "feat: Loop 5步模板创作 + 导入自动解析"
```

---

### Task 4.3: HomeView LoopPickerTrigger + AppChatView AppLoopInjectBar

**Files:**
- Create: `ai-generate-code-frontend/src/components/loop/LoopPickerTrigger.vue`
- Create: `ai-generate-code-frontend/src/components/loop/AppLoopInjectBar.vue`
- Modify: `ai-generate-code-frontend/src/page/HomeView.vue`
- Modify: `ai-generate-code-frontend/src/page/App/AppChatView.vue`

- [ ] **Step 1: LoopPickerTrigger**

```vue
<template>
  <a-popover v-model:visible="visible" trigger="click" placement="top">
    <template #content>
      <div v-if="loopList.length === 0" class="picker-empty">
        <p>暂无 Loop，前往市场添加</p>
        <a-button type="link" @click="$router.push('/loop')">前往 Loop 市场</a-button>
      </div>
      <div v-else class="picker-list">
        <a-checkbox-group v-model:value="selectedIds">
          <div v-for="loop in loopList" :key="loop.id" class="picker-item">
            <a-checkbox :value="loop.id">{{ loop.loopName }}</a-checkbox>
            <span class="picker-desc">{{ loop.description }}</span>
          </div>
        </a-checkbox-group>
      </div>
    </template>
    <a-button class="loop-pill" :type="selectedIds.length > 0 ? 'primary' : 'default'">
      Loop{{ selectedIds.length > 0 ? ' · ' + selectedIds.length : '' }}
    </a-button>
  </a-popover>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const visible = ref(false)
const loopList = ref<any[]>([])
const selectedIds = ref<number[]>([])

const emit = defineEmits<{
  (e: 'change', ids: number[]): void
}>()

watch(selectedIds, (val) => emit('change', val))

onMounted(async () => {
  // 加载用户自己创建的 Loop（简要列表）
  // loopController.myListPage({ pageCurrent: 1, pageSize: 50 })
  //   .then(res => loopList.value = res.data || [])
})

defineExpose({ selectedIds })
</script>

<style scoped>
.picker-empty { text-align: center; padding: 16px; min-width: 200px; }
.picker-list { max-height: 300px; overflow-y: auto; min-width: 220px; }
.picker-item { padding: 6px 0; }
.picker-desc { font-size: 12px; color: var(--text-secondary); margin-left: 24px; display: block; }
</style>
```

- [ ] **Step 2: AppLoopInjectBar**

```vue
<template>
  <div v-if="loopList.length > 0" class="loop-inject-bar">
    <div v-if="loopList.length > 20" class="inject-search">
      <a-input v-model:value="searchText" placeholder="搜索 Loop…" size="small" allow-clear />
    </div>
    <a-radio-group v-model:value="selectedLoopId" class="inject-radio-group">
      <a-radio-button :value="0">无</a-radio-button>
      <a-radio-button v-for="loop in filteredList" :key="loop.loopId" :value="loop.loopId">
        {{ loop.loopName }}
      </a-radio-button>
    </a-radio-group>
    <a-button type="link" size="small" @click="showMarketAdd = true">+ 添加</a-button>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'

const props = defineProps<{ appId: number }>()
const emit = defineEmits<{
  (e: 'select', loopId: number): void
}>()

const loopList = ref<any[]>([])
const selectedLoopId = ref(0)
const searchText = ref('')
const showMarketAdd = ref(false)

const filteredList = computed(() => {
  if (!searchText.value) return loopList.value
  const q = searchText.value.toLowerCase()
  return loopList.value.filter((l: any) => l.loopName?.toLowerCase().includes(q))
})

watch(selectedLoopId, (val) => emit('select', val))

onMounted(async () => {
  // appLoopController.listVO({ appId: props.appId })
  //   .then(res => loopList.value = res.data || [])
})
</script>

<style scoped>
.loop-inject-bar { display: flex; align-items: center; gap: 8px; padding: 8px 16px; border-top: 1px solid var(--border-color); }
.inject-search { width: 160px; }
.inject-radio-group { display: flex; flex-wrap: wrap; gap: 4px; }
</style>
```

- [ ] **Step 3: HomeView 插入 LoopPickerTrigger**

在 `HomeView.vue` 的 `?` 按钮与工作流生成按钮之间插入：
```vue
<LoopPickerTrigger @change="onLoopChange" ref="loopPickerRef" />
```

在 script setup 中引入：
```typescript
import LoopPickerTrigger from '@/components/loop/LoopPickerTrigger.vue'
```

创建应用时读取 `loopPickerRef.value?.selectedIds` 传入 `appAddRequest.loopIds`。

- [ ] **Step 4: AppChatView 插入 AppLoopInjectBar**

在聊天输入区上方插入：
```vue
<AppLoopInjectBar v-if="appId" :appId="appId" @select="onLoopSelect" />
```

在 `sendMessage` 构建 URL 时：
```typescript
const loopId = selectedLoopId.value || undefined
const url = `/api/chat/gen/code?appId=${appId}&message=${encodeURIComponent(msg)}${loopId ? `&loopId=${loopId}` : ''}`
```

- [ ] **Step 5: 提交**

```bash
git add ai-generate-code-frontend/src/components/loop/ ai-generate-code-frontend/src/page/HomeView.vue ai-generate-code-frontend/src/page/App/AppChatView.vue
git commit -m "feat: LoopPickerTrigger + AppLoopInjectBar 集成"
```

---

### Task 4.4: AdminLoopManage + AdminApplyManage 扩展

**Files:**
- Create: `ai-generate-code-frontend/src/page/Admin/AdminLoopManage.vue`
- Modify: `ai-generate-code-frontend/src/page/Admin/AdminApplyManage.vue`

- [ ] **Step 1: AdminLoopManage 页

```vue
<template>
  <div class="admin-loop">
    <h1>Loop 管理</h1>
    <a-table :dataSource="loopList" :columns="columns" rowKey="id">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">
          <a-button size="small" @click="editPriority(record)">设精选</a-button>
          <a-button size="small" danger @click="handleDelete(record.id)">删除</a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

const loopList = ref([])
const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id' },
  { title: '名称', dataIndex: 'loopName', key: 'loopName' },
  { title: '用户', dataIndex: 'userId', key: 'userId' },
  { title: '优先级', dataIndex: 'priority', key: 'priority' },
  { title: '可见性', dataIndex: 'visibility', key: 'visibility' },
  { title: '操作', key: 'action' },
]

onMounted(async () => {
  // adminListPage
})
const editPriority = (record: any) => {
  // adminUpdate({ id: record.id, priority: 99 })
  // 成功后提示"已通过，Loop 已上架精选"
}
const handleDelete = (id: number) => {
  // /loop/delete?id=${id}
}
</script>
```

- [ ] **Step 2: AdminApplyManage 添加 Loop 申请 Tab

在 `AdminApplyManage.vue` 的 tabs 中加一个 tab-pane 用于 Loop 申请：
```vue
<a-tab-pane key="loop-apply" tab="Loop 申请">
  <a-table :dataSource="loopApplyList" :columns="loopApplyColumns" rowKey="id">
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'action'">
        <a-button type="primary" size="small" @click="approveLoop(record)">通过</a-button>
        <a-button size="small" @click="rejectLoop(record)">拒绝</a-button>
      </template>
    </template>
  </a-table>
</a-tab-pane>
```

```typescript
const loopApplyList = ref([])
const loopApplyColumns = [
  { title: 'Loop ID', dataIndex: 'loopId', key: 'loopId' },
  { title: '用户', dataIndex: 'userId', key: 'userId' },
  { title: '理由', dataIndex: 'applyReason', key: 'applyReason' },
  { title: '操作', key: 'action' },
]

const approveLoop = async (record: any) => {
  await loopController.approveApply(record.id)
  // 响应提示「已通过，Loop 已上架精选」
  message.success('已通过，Loop 已上架精选')
  loadLoopApplys()
}
```

- [ ] **Step 3: 提交**

```bash
git add ai-generate-code-frontend/src/page/Admin/AdminLoopManage.vue ai-generate-code-frontend/src/page/Admin/AdminApplyManage.vue
git commit -m "feat: 管理端 Loop 管理 + 审批扩展"
```

---

## Batch 5：集成验证

### Task 5.1: openapi2ts + 编译验证

- [ ] **Step 1: 启动后端，运行 openapi2ts**

```bash
# 启动后端（新终端）
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code"
start .\mvnw.cmd spring-boot:run
# 等待启动后执行
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend"
npm run openapi2ts
```
Expected：生成 `src/api/loopController.ts` 等文件

- [ ] **Step 2: 验证前端编译**

```bash
npm run build
```
Expected: BUILD SUCCESS

- [ ] **Step 3: 验证后端编译**

```bash
cd "d:\mainJava\all Code\program\glyahh-ai-generate-code"
.\mvnw.cmd -q -DskipTests compile
```
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add ai-generate-code-frontend/src/api/  # 新生成的文件
git commit -m "chore: openapi2ts 生成 Loop API 客户端"
```

### Task 5.2: 全链路验收

- [ ] 验收点 1：未登录访问 `/loop` → 重定向登录
- [ ] 验收点 2：创建 Loop 不填名称 → 前端阻止 + 后端校验
- [ ] 验收点 3：导入非法格式 → 报 PARAMS_ERROR
- [ ] 验收点 4：创建应用不选 Loop → 正常创建，loopIds 为空
- [ ] 验收点 5：聊天不选 Loop → SSE 无 loopId 参数，不注入
- [ ] 验收点 6：传入非法 loopId → 跳过注入，不报错
- [ ] 验收点 7：精选列表 @Cacheable 5min → 改 priority 后清缓存
- [ ] 验收点 8：Redis 空值占位 → 未命中写 "{}" TTL 60s
- [ ] 验收点 9：createApp + bindLoops 事务性 → @Transactional
- [ ] 验收点 10：亮暗主题 → CSS 变量兼容
- [ ] 验收点 11：375px → 无横向滚动

- [ ] **全链路提交**

```bash
git add -A && git commit -m "feat: Loop 全链路实现"
```
