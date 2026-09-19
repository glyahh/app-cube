# 用户个性化配置设计文档

> **日期：** 2026-06-16
> **状态：** 已定稿
> **对应需求：** 用户在个人设置页配置"应用风格"与"回答风格"，后端存 MySQL + Redis 缓存，代码生成时注入 userMessage

## 1. 目标

在「个人设置」页左侧导航新增「个性化」板块，让用户可配置「应用风格」与「回答风格」两类 prompt；后端新建 MySQL 表按用户存储并可更新，经 Redis 缓存后在进入生成链路时统一拼接注入 userMessage。

## 2. 架构图

略（已在对话中确认）

## 3. 数据流

1. 用户打开 /user/settings → 点击个性化 → GET 加载已保存配置
2. 用户编辑并保存 → PUT 提交 → 后端 upsert MySQL + 删除 Redis 缓存
3. 用户发送生成请求 → ChatToGenCodeImpl → injectPromptIfPresent() 加载个性化 prompt → 拼接前缀 → 传入 Facade

### 注入格式

`
[user_app_style]
（说明：以下为你的应用风格偏好，优先级低于本轮显式指令、高于系统默认）
用户偏好：{appStyle内容}

[user_answer_style]
（说明：以下为你的回答风格偏好，优先级低于本轮显式指令、高于系统默认）
用户偏好：{answerStyle内容}
`

空配置（任一字段为空时跳过该字段的块），两者都空时不拼接。

## 4. 数据库

见对话确认的表结构：user_personalization 表，字段 userId, pp_style, nswer_style, isDelete（逻辑删除）。

## 5. Redis 缓存

| 项目 | 值 |
|------|-----|
| Key 格式 | user:pers:{userId} |
| 值格式 | JSON: {"appStyle":"...","answerStyle":"..."} |
| 正常 TTL | 2 小时 + 随机 0~10 分钟抖动 |
| 空值 TTL | 60 秒（防穿透） |
| 缓存策略 | Cache-aside: 读优先缓存，miss 回源 MySQL 并回填 |
| 失效策略 | 写 MySQL 后直接 delete 缓存 key |

## 6. 后端模块

见对话确认的文件清单。常量类中定义 key 前缀、TTL、字符上限。

## 7. 前端变更

在 UserSettings.vue 增加「个性化」菜单项和表单（两个多行文本域 + 保存按钮），新建 API 调用。

## 8. 风险与缓解

见对话确认的 6 项风险缓解策略。
