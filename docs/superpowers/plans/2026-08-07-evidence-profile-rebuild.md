# Evidence Profile Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:super_run_plan to implement this plan task-by-task.

**Goal:** 将 `ai-generate-code` 重构为可验证的 AI 软件合成与制品交付项目展示，并建立能帮助用户记忆、复盘和面试表达的工程日志与行动手册。

**Architecture:** 不修改业务代码和历史提交，只新增证据型 README、工程案例文档、个人主页 README 和 Word 行动手册。项目案例以源码路径、提交短 SHA 和“已实现/待验证”状态建立证据链；个人学习路线以一个旗舰项目和真实 Linux 演示环境为主线。

**Tech Stack:** Markdown、GitHub Profile README、Python `python-docx`、PowerShell、Git/GitHub CLI。

---

### Task 1: 创建隔离分支并保留基线

**Files:**
- Branch: `docs/evidence-profile-rebuild`
- Baseline: current `master` at `c225ee0`

- [ ] Create the branch without rewriting history.
- [ ] Confirm the worktree is clean before edits.

### Task 2: 重写项目 README

**Files:**
- Modify: `README.md`

- [ ] Replace the feature dump with project problem, positioning, architecture flow, verified evidence, boundaries and quick start.
- [ ] Use evidence states instead of unsupported production claims.
- [ ] Include the iframe fallback, personalization-memory consistency, streaming tool state machine, layered memory, RAG and Vue build validation cases.

### Task 3: 建立工程记忆系统

**Files:**
- Create: `docs/engineering-journal/README.md`
- Create: `docs/engineering-journal/decision-template.md`
- Create: `docs/engineering-journal/incident-template.md`
- Create: `docs/engineering-journal/interview-cards.md`
- Create: `docs/engineering-journal/cases/*.md`

- [ ] Make every case record phenomenon, root cause, decision, verification and residual risk.
- [ ] Add source paths and short commit evidence for confirmed findings.
- [ ] Add a 90-second interview answer and a fast recovery checklist for each case.

### Task 4: 把 Linux 改成真实项目演示路线

**Files:**
- Create: `docs/engineering-journal/linux-lab.md`

- [ ] Define one reproducible local lab using the project stack: Java service, MySQL, Redis, Docker, logs, ports and resource observation.
- [ ] Teach only task-driven commands: start, inspect, diagnose, restart and verify.
- [ ] Add a 7-day sequence with expected observations and evidence to save.

### Task 5: 更新 GitHub 展示层

**Files:**
- Create/update profile repository README: `glyahh/glyahh/README.md` through GitHub CLI/API.

- [ ] Set repository description, topics and profile bio with evidence-based wording.
- [ ] Create a profile README only if the profile repository does not exist.
- [ ] Do not rewrite old commits and do not merge the pull request automatically.

### Task 6: 生成 Word 行动手册

**Files:**
- Create: `outputs/Java_AI应用工程成长与求职行动手册.docx`

- [ ] Include the positioning, memory workflow, seven engineering cases, Linux lab, Python/FastAPI, Java concurrency, AI application courses, AI Gateway roadmap, job-search plan and daily checklists.
- [ ] Verify the DOCX XML and remove placeholders.

### Task 7: 验证、提交、推送和创建 PR

- [ ] Run `git diff --check`, UTF-8/garbled-text scan and internal-link checks.
- [ ] Verify every case has code or commit evidence.
- [ ] Commit only the documentation changes, push the branch and create a PR.
- [ ] Report the PR, changed files, Word link and rollback command; leave the PR unmerged.
