# 缺陷防复发测试映射

来源：`docs/reports/bugfixes/eleven-bugs-20261026.md`。这些测试都在默认 PR 检查内运行；`external` 标签的旧环境验收不作为映射。

| 缺陷                            | 防复发测试                                                                    |
| ------------------------------- | ----------------------------------------------------------------------------- |
| F01 UPSERT 截断与整批回滚       | `AgentChangeLedgerRepositoryRegressionTest`、`AgentChangeRollbackServiceTest` |
| F02 发布只允许公共资源          | `PublicDeployFilesTest`、`ImportSourcePublicationGateTest`                    |
| F03 生成入口先校验应用归属      | `AgentOwnershipBeforeWriteTest`                                               |
| F04 普通应用高风险逐次授权      | `AgentExecutePermissionRegressionTest`                                        |
| F05 切换传输使旧回调失效        | `agentStreamTransport.test.ts`                                                |
| F06 保存绑定 app/路径/代次/内容 | `ProjectFilesModal.test.ts`                                                   |
| F07 文件树和读取按代次落地      | `ProjectFilesModal.test.ts`                                                   |
| F08 失败停止保留执行身份        | `ImportExecutionRunnerTest`                                                   |
| F09 停止绑定原计划版本与归属    | `ImportStartupPlanServiceTest`、`ImportStartupServiceTest`                    |
| F10 完整终稿与取消摘要          | `test_stream_completion_regression.py`、`agentEventMapping.test.ts`           |
| F11 普通与导入应用的保存语义    | `AgentPermissions.test.ts`、`useAgentSessionReview.test.ts`                   |

新增缺陷修复时，先写能复现失败的最小测试，再修复，并在本表登记。不要用删除断言、无条件跳过或只保留手工步骤代替。
