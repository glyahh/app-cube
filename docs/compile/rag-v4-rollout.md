# RAG text-embedding-v4 上线步骤

本次切换不迁移 `text-embedding-v3` 向量。新生成或修改的文件只写入
`code_chunks_v4`，旧 `code_chunks` 索引在确认新索引映射后人工删除。

## 1. 配置 ES 地址

共享配置默认连接 `http://localhost:9200`。非标准端口通过环境变量覆盖：

```powershell
$env:RAG_ELASTICSEARCH_URIS = 'http://localhost:9201'
```

local-a/local-b profile 已在各自的本地配置中覆盖为 `9201`。

## 2. 创建并核对 v4 索引

启动应用后，`RagElasticsearchIndexInitializer` 会创建 `code_chunks_v4`。

```powershell
$es = if ($env:RAG_ELASTICSEARCH_URIS) { $env:RAG_ELASTICSEARCH_URIS } else { 'http://localhost:9200' }
$mapping = Invoke-RestMethod -Method Get -Uri "$es/code_chunks_v4/_mapping"
$dims = $mapping.code_chunks_v4.mappings.properties.embedding.dims
if ($dims -ne 1024) { throw "Unexpected embedding dimension: $dims" }
```

## 3. 删除 v3 索引

仅在上一步确认 `code_chunks_v4` 的 `embedding.dims` 为 `1024` 后执行：

```powershell
Invoke-RestMethod -Method Delete -Uri "$es/code_chunks"
Invoke-RestMethod -Method Get -Uri "$es/_cat/indices/code_chunks*?format=json"
```

删除操作只影响 ES 中的旧向量，不影响 MySQL、会话记录或
`temp/code_output` 下的生成源码。历史项目不会自动补索引，后续发生文件变更时才会写入 v4 索引。
