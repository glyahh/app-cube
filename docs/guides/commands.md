# 项目编译与启动命令速查

> 环境：Git Bash（Windows 下 PowerShell 见「Windows 等价命令」列）  
> 后端默认：`http://localhost:8124/api`  
> 前端默认：`http://localhost:5173`（`/api`、`/static` 代理到 8124）

---

## 前端（Vite）— 目录 `ai-generate-code-frontend/`

| 场景 | Git Bash 命令 | 说明 |
|------|---------------|------|
| 首次安装依赖 | `cd ai-generate-code-frontend && npm ci` | Node 要求 `^20.19.0 \|\| >=22.12.0` |
| 开发启动（热更新 HMR） | `cd ai-generate-code-frontend && npm run dev` | 保存 `.vue/.ts` 自动刷新 |
| 生产构建 | `cd ai-generate-code-frontend && npm run build` | `vue-tsc --build` + `vite build` |
| 仅类型检查 | `cd ai-generate-code-frontend && npm run type-check` | 不产出 dist |
| 仅 Vite 打包 | `cd ai-generate-code-frontend && npm run build-only` | 跳过 type-check |
| 构建预览 | `cd ai-generate-code-frontend && npm run preview` | 预览 build 产物 |
| 代码检查 | `cd ai-generate-code-frontend && npm run lint` | oxlint → eslint |
| 代码格式化 | `cd ai-generate-code-frontend && npm run format` | Prettier 写回 `src/` |
| OpenAPI 生成 TS | `cd ai-generate-code-frontend && npm run openapi2ts` | 需后端已启动（`/api/v3/api-docs`） |

---

## 后端（Spring Boot）— 目录仓库根目录

| 场景 | Git Bash 命令 | Windows PowerShell 等价命令 | 说明 |
|------|---------------|------------------------------|------|
| 开发启动 | `./mvnw spring-boot:run` | `.\mvnw.cmd spring-boot:run` | 端口 **8124**，上下文 **`/api`** |
| 仅编译（不运行） | `./mvnw -q -DskipTests compile` | `.\mvnw.cmd -q -DskipTests compile` | 快速检查编译错误 |
| 完整打包 | `./mvnw clean package` | `.\mvnw.cmd clean package` | 含测试 |
| 打包（跳过测试） | `./mvnw clean package -DskipTests` | `.\mvnw.cmd clean package -DskipTests` | 快速出 jar |
| 运行全部测试 | `./mvnw test` | `.\mvnw.cmd test` | 全量测试 |
| 运行单个测试类 | `./mvnw test -Dtest=ClassName` | `.\mvnw.cmd test -Dtest=ClassName` | 替换 `ClassName` |
| 运行单个测试方法 | `./mvnw test -Dtest="ClassName#methodName"` | `.\mvnw.cmd test -Dtest="ClassName#methodName"` | 替换类名与方法名 |

---

## 热更新对照

| 端 | 是否支持 | 命令 / 方式 | 备注 |
|----|----------|-------------|------|
| 前端 | ✅ 支持 | `npm run dev` | Vite HMR，改代码自动生效 |
| 后端 | ❌ 当前不支持 | 改 Java 后需**手动重启** `spring-boot:run` | `spring-boot-devtools` 仍禁用；需保护 Agent 子进程池的关闭与重建生命周期 |

---

## 典型双终端开发

| 终端 | 工作目录 | 命令 |
|------|----------|------|
| 终端 1（后端） | 仓库根目录 | `./mvnw spring-boot:run` |
| 终端 2（前端） | `ai-generate-code-frontend/` | `npm run dev` |

后端启动优先使用仓库根目录的 `scripts/start-backend.ps1`，修复后使用 `scripts/restart-backend.ps1`；脚本含环境预检。需要依赖时使用 `scripts/start-all.ps1`。配置与凭据保留在本地 `.env`，不要覆盖。

浏览器访问：`http://localhost:5173`