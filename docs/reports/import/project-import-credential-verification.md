# 导入凭据：后端阶段验证

2026-10-04 来源撤销增量：`ImportCredentialService` 在原凭据短事务内汇总准备来源与关联主任务，`UNION` 避免同 UUID 重复停止；事务结束后优先由来源协调器核实停止/清理，不回退主任务接口掩盖来源停止失败。改备注不撤销，成功应用保留。`credential-source-revocation-tests.log` 共 31 项相关测试通过；`credential-source-revocation-live/result.json` 退出 0、39 项真实接口检查通过，新增无主任务的准备来源替换、删除及无关来源不变。来源由专用账号的数据库受控绑定建立，不请求平台、不证明私有认证。后端 PID 32588、8124 监听，`restart-source-revocation.log` 保留实际重启结果。`git-public-business-regression.log` 的本轮选中 Java 回归 21 类、190 项、0 失败/错误/跳过，退出 0；只对应当时源码，后续过期门禁增量须另验。

公开 Git 拉取与最终 App 局部证据已新增，见[双平台公开来源验证](project-import-git-public-verification.md)。下方“Git 拉取未完成”对应历史阶段，私有认证与正式 Git 前端仍未完成。

2026-10-04，`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。用户已确认精简后的 Git/凭据前端并批准接后端。本记录仅凭据存储、版本与提交撤销边界；**Git 拉取、正式界面联调和整项未完成**。

## 已实现

- 会话所有者的多份 GitHub/Gitee 凭据；仅返回 UUID、平台、备注、语义版本、记录 revision 和状态。新建、改备注、替换和删除为独立操作，冲突不覆盖。
- JCA AES-256-GCM、独立 96-bit nonce、认证标签；AAD 绑定编码版本/owner/provider/credentialId/语义版本；数据库 nonce 唯一约束。输入 Token 限 4096 个可打印 ASCII 字符，不允许空白/控制字符；明文不进响应、DTO 日志或数据库。
- 备注只变 recordRevision；替换保留备注、新增语义版本、撤销旧版与关联 lease；删除清除该凭据全部版本的密文/keyId/nonce，保留无秘密审计。
- 变更与最终应用提交共用凭据行锁；撤销先提交则不能 reserve/finish/recover，新 App 不产生。成功先提交后替换/删除不删除原应用。
- 资格撤销事务结束后才请求实际任务停止。返回每任务真实停止/清理状态；不能核实时显式 STOP_FAILED/UNVERIFIED，不伪装已取消。
- 写请求核对当前 ownerId、CSRF、Origin 与实际读取元数据上限。Token DTO 禁止 JSON 回写且覆盖 toString；备注接口采用既有 CORS 允许的 PUT，不为新接口扩大全局 CORS。

路由：`GET/POST /api/project/import/credentials`；`PUT /{id}/name`、`PUT /{id}/token`；`DELETE /{id}?ownerId=...&revision=...`。版本与 ID 返回 string。没有通用解密 HTTP 接口，没有自动使用 Codex 或其他宿主凭据。

## 密钥配置与约束

本机开发新建独立密钥文件 `D:\codex\WorkDocument\app-cube-local-secrets\import-keyring.json`，目录 ACL 禁继承，仅当前用户与 SYSTEM；密钥文件继承此受限 ACL。仓库 `.env` 只追加 `project-import.credential-key-ring-file` 路径，不写密钥值、不改原配置。密钥文件不在源码、导入工作区或业务库，不进入模型输入；未打印或提交。

文件格式为 `activeKeyId` 与 `keys`，keys 中每项为 32-byte 密钥的 Base64。缺失/损坏/未知 keyId/AAD/tag 失败明确阻断，不生成默认密钥、不尝试其他 Token。加载拒绝仓库内路径、链接、过大文件及不合格密钥。须单独保护和备份该文件；丢失后不能凭密文恢复 Token，不能重新生成密钥假称原凭据仍可用。

纯加密器已验证旧 keyId 解密和换 keyId 重封装不改语义版本；**尚未实现线上批量密钥轮换任务**。JVM String/JSON 解析存在必要内存副本，未声称能彻底擦除；部署进程权限仍由操作环境保护。

## 实际证据

后续增量：凭据编辑 `revision` 必须显式提交，缺失不再隐式采用 0。`restart-revision-qualified.log` 重启成功、端口 8124 PID 39744；`credentials-revision-qualified/result.json` 实际 35 项通过，包含缺失 revision 的拒绝验证。该轮仍仅使用虚构凭据和专用账号，不是私有平台认证通过。

证据根 `D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\import-backend-publication-20261004\`：

- `credential-cipher-red.log`、`credential-service-red.log`、`repository-source-red.log`：测试先行预期失败。
- `credential-source-tests.log`：退出 0，包含 cipher 6、service 6、controller 3、keyProvider 3、sourceParser 3，及任务/路径/门禁/控制器回归。Mock 仓库只证明断言，不当真实事务证据。
- `restart-credential-source.log` 与服务日志：实际重启，8124 服务 PID 43852；Flyway V15 已应用。
- `credentials-http-live/result.json`：退出 0，34 个实际检查 PASS；新专用普通账号、同平台多份、跨用户/CSRF/过期 revision、数据库密文、改备注、替换后实际关联任务 CANCELLED/DONE 与磁盘清理、无关任务不变、已完成 App 保留、撤销先提交阻断、删除清除秘密。
- 租约由脚本只在专用任务上受控绑定；**不是 Git 网络读取成功**，不是私有平台验收。全部 Token 为专用虚构标记，未请求任何仓库。
- 首次真实检查失败保留：PATCH 被现有 CORS 拒绝，改为 PUT；一次启动中连接被拒，待真实启动完成后重跑。测试先行新增未实现类与后台启动 test-compile 相遇导致一次重启失败，已实现、重测并恢复服务；未把失败日志删除或记为通过。

## 重跑、未完成及回退

```powershell
.\mvnw.cmd -q "-Dtest=ImportCredentialCipherTest,ImportCredentialServiceTest,ImportCredentialControllerTest,ImportCredentialKeyProviderTest,ImportRepositorySourceTest" test
.\agent\.venv\Scripts\python.exe scripts/checks/verify-import-credentials.py --output temp/<新的专用证据目录>
```

脚本使用现有后端/数据库，生成两个专用账号，成功测试 App 保留；完成流程清除本次虚构凭据秘密。失败轮次的专用账号/虚构记录保留并如实记录，不扫用户数据。

下一步：受管 Git 网络与进程、严格目的地/重定向/宿主配置隔离、全分支及固定 commit、对象导出/完整性、Git 凭据租约各阶段停止、正式页面真实联调。主机 DNS 当前对平台返回 `198.18.*` 合成地址，属于实际网络环境变量，尚不能将 DNS/真实目的地验证记为通过；不能简单放开任意私网或关闭 TLS 校验。只读命令行 Git 已核对为 `2.45.1.windows.1`，未安装 Git 库。

Gitee 官网仓库页面明确 HTTPS 用户名加 PAT，[官方仓库/SDK页面](https://gitee.com/sdk/gitee5j/blob/main/docs/RepositoriesApi.md)；官方 Swagger `https://gitee.com/api/v5/swagger_doc.json` 本机读取为 5.4.93，原始文档保存 `gitee-swagger.json`。这不证明实际私有认证已验收；不凭非官方示例将 Token 放入 URL。

58 个整组仍 NOT_RUN；真正私有仓库验收继续按用户要求暂缓。识别/安装/多服务和其他三项未展开，导入旧执行入口仍阻断。回退保留现有用户修改、测试 App、V15/Flyway 历史；不要 DROP 凭据表或删密钥后留下不可解密记录。只有确认不再需要历史密文或独立完成迁移后，才能撤销 `.env` 新配置与密钥文件；不默认删除。
