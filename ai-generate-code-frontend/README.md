# 应用魔方前端

Vue 3 + Vite + TypeScript 的聊天与项目工作台。项目总览见[根 README](../README.md)，文档分类见[总索引](../docs/README.md)，能力与验证状态见[当前状态](../docs/status/current.md)。

## 本地开发

Node 版本要求以 `package.json` 为准：`^20.19.0 || >=22.12.0`。在本目录运行：

```sh
npm ci
npm run dev
```

默认访问 `http://localhost:5173`。Vite 将 `/api`（含 WebSocket）和 `/static` 转发到 `localhost:8124`；需要真实业务时，从仓库根目录运行 `scripts/start-backend.ps1`，依赖准备见[启动命令](../docs/guides/commands.md)。

## 常用命令

| 命令 | 用途 |
|---|---|
| `npm run type-check` | Vue 与 TypeScript 类型检查 |
| `npm run build` | 类型检查与生产构建 |
| `npm run test:unit` | Vitest 单元测试 |
| `npm run test:perf` | Playwright 性能测试，先核对真实环境要求 |
| `npm run audit:initial-load` | 构建并检查首屏产物预算 |
| `npm run openapi2ts` | 从运行中的后端接口生成类型 |
| `npm run lint` | oxlint 与 ESLint，会自动修改文件 |
| `npm run format` | Prettier，会修改文件 |

按改动运行相关测试，不将构建通过等同于业务验收。长对话性能脚本及前后数据见[性能报告](../docs/reports/agent/chat-agent-performance-20261020.md)。

## 开发约定

- 界面文案遵守[文案规范](../docs/rules/ui-copy.md)，设计资料见[前端设计](../docs/plans/frontend.md)。
- 流式事件和跨进程约定见[Agent 契约](../docs/contracts/agent.md)。
- 推荐 VS Code 与 Vue (Official) 扩展；Vue 类型检查使用 `vue-tsc`，以项目脚本为准。
- 静态预览和运行容器预览的隔离不同，不一概视为同源。
