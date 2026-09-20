# FZU-Policy-RAG 开发记录

## 记录原则

本文件记录影响项目设计、行为、可靠性和后续判断的重要开发过程。内容使用中文，保留真实背景、方案取舍、验证结果和限制，不代替 Git commit log，也不记录普通语法错误、格式化或低价值操作过程。

## 已完成的重要里程碑

以下摘要仅根据当前 Git 历史确认：

- `aa64bf8`：从开源 Hybrid RAG 项目初始化 FZU-Policy-RAG。
- `8581f89`：完成 baseline 编译修复并补充项目开发规范。
- `a9ca41a`：接入百炼混合向量模型。
- `a82f0be`：接入百炼文本重排序模型。
- `6cd2f37`：补充 baseline 数据库初始化脚本。
- `2c76461`：清理本地模型依赖并打通百炼 baseline 链路，完成真实福大政策文档 E2E 验证。

## 2026-09-19｜建立 FZU Crawler V1

### 开发目标

面向福州大学教务处公开页面，稳定发现并提交高价值本科生政策资料，复用现有 MinIO、`knowledge_document`、`etl_job` 和 ETL/Hybrid RAG 主链。

### 背景与问题

baseline 已能处理人工上传文件，但网页正文和附件没有统一的发现、筛选与来源元数据入口。Crawler V1 只解决福州大学教务处单站点的有限范围采集，不承担通用爬虫平台、政策版本判断或 LLM 分类职责。

### 方案与取舍

采用 Jsoup 顺序抓取 HTML，使用 JDK 17 `HttpClient` 下载二进制附件。列表跟随页面提供的“下页”链接，使用标题关键词白名单做 include/skip，HTML 正文规范化为临时 Markdown 后进入现有 ingestion。附件只接收 PDF/DOC/DOCX，并跳过申请表、名单、模板等事务性文件。

Crawler 与 HTTP 上传统一复用 Path-based ingestion service，继续使用 SHA-256 去重、MinIO、文档记录和 ETL job，不增加下载目录、Crawler 任务表或第二套去重机制。来源字段只作为文档和 Milvus metadata 的确定性副本，本轮不做业务 metadata hard filter，也不改检索算法。

### 实现结果

最终结构为 `FzuJwcCrawler`、`FzuCrawlService`、`FzuCrawlerController`，并新增具体的 `DocumentIngestionService` 复用 HTTP 上传与 crawler 的 Path 入口。数据库新增 `002_fzu_crawler_metadata.sql`，来源元数据同步到 `Document` 和每个 Milvus child metadata；检索阶段没有增加业务 hard filter。

### 真实验证

小规模真实 discovery 已访问 5 个有限列表页：2025/2024/2023 学生手册各 1 页、教学文件 1 页、教学通知 1 页。结果为：扫描页 5，发现详情 100，相关详情 37，跳过 63，解析失败 0，生成 HTML Markdown artifact 37。该阶段未写数据库。

随后进行真实 crawl + ETL smoke。MySQL 迁移和连接问题已定位：迁移文件最初使用的 `ADD COLUMN IF NOT EXISTS` 不被本机 MySQL 8.0.36 接受，改为明确的单次 `ADD COLUMN` 后五个字段创建成功。测试进程覆盖正确的 MySQL 本地凭据后，真实 crawl 能访问页面，但本机应用配置中的 MinIO 占位凭据无效，37 个 artifact 均在 MinIO 写入前失败；替换为 Compose 中的 `minioadmin` 凭据后，测试 JVM 又受到本机 IDE Java 进程占用的 native memory 限制，未完成最终 ETL smoke。因此本轮没有虚构 `COMPLETED`、Parent Block、Milvus Child 或 RAG 回归指标，真实入库结果仍待在可用的本地 MinIO 凭据和足够 JVM 资源下重跑。

真实页面还发现部分详情页 `.articelMain` 的首个标题节点是正文章节而非文档标题。最终采用列表项标题作为稳定标题来源，正文标题只在列表标题为空时回退，避免生成“第一章 总则.md”这类错误文档名。

### 踩坑与方案演进

仅记录真实发生且影响设计或可靠性的站点结构、附件、分页、ETL 或元数据问题；没有发生的问题不虚构。

### 测试 / 测评记录

Parser/filter 定向测试通过；公共 Path ingestion 与既有 FilePart 上传回归在显式 Byte Buddy agent 参数下通过。`mvn -q -DskipTests compile` 通过。真实 discovery smoke 通过；真实 crawl+ETL 受 MinIO 凭据和 JVM native memory 环境限制未完成。

### 当前限制

V1 仅覆盖福州大学教务处三个确定栏目和有限通知页数；不实现定时采集、版本有效性判断、主题分类、OCR、表格/压缩包解析或检索 hard filter。真实入库和 RAG 回归需要在本机配置有效 MinIO 凭据并释放足够 JVM 内存后重跑。

### 后续

在 baseline 和真实采集数据稳定后，根据真实 Failure Case 决定是否进入 Version / Validity-aware Retrieval 或 Claim-Evidence Verification。

## 2026-09-19｜Crawler V1真实 ingestion / RAG 闭环收尾

### 本轮目标

将 Crawler V1 从 discovery 验证推进到真实的 `Crawler → MinIO → ETL → Milvus → RAG` 闭环，并只修正本轮真实验证中发现的小问题，不改变现有检索主链。

### 已知问题修正

- `FzuCrawlService` 的 `failed` 改为包含 discovery 阶段失败数和 artifact 提交失败数；`failures` 仍保留具体错误文本。
- 奖学金从 Crawler V1 关键词范围移除，当前范围聚焦本科教学、学籍、培养、课程、成绩、学分和毕业政策。
- 清理 `DocumentUploadHandler` 为旧测试保留的兼容构造和 `configureUploadProperties` 运行时重配；HTTP 上传与 Crawler 继续注入同一个 `DocumentIngestionService`，上传限制仍由 Spring 配置注入到 ingestion service。
- 真实正常规模抽查发现标题包含“名单”的公告会被“选课/辅修/毕业”等关键词误收，新增最小标题黑名单并补回归测试；没有引入 LLM 分类或 topic/role 抽象。

### 环境与真实验证

之前的 MinIO 凭据不匹配和 JVM native memory 问题在本轮通过核对现有容器配置、补齐本地进程环境变量和释放资源后未再次阻断验证；没有修改业务实现或提交本机专用内存配置。复用已有 `milvus-standalone`、`milvus-etcd` 和 `milvus-minio`，MinIO 实际 endpoint 为本机 `9000`，bucket 为 `fzu-policy-documents`。本地 MySQL、Redis、Milvus、MinIO 均可连接，DashScope embedding 和 qwen-flash 均实际调用成功。

小规模采集使用三个 crawler 页数上限均为 1，实际结果为：扫描页 5，发现 100，相关 32，提交 32，重复 0，跳过 68，失败 0；artifact 为 HTML 32、PDF 0、DOC 0、DOCX 0。32 个文档均写入 MinIO、`knowledge_document` 和 `etl_job`，最终为 `COMPLETED` / `SUCCESS`，Parent Block 32 个。

随后恢复默认页数进行正常规模幂等采集，结果为：扫描页 34，发现 626，相关 149，提交 117，重复 32，跳过 485，失败 0；artifact 为 HTML 149、PDF 0、DOC 0、DOCX 0。后台 ETL 最终为 149 个 `COMPLETED` 文档、149 个 `SUCCESS` job、149 个 Parent Block。HybridVectorWriter 日志确认 child hybrid vector 写入 Milvus；应用启动时确认目标 collection 已包含 `sparse_vector`，embedding 维度为 1024。

真实 RAG 回归使用转专业政策和课程替代/学分认定政策各提问一次。两次请求都经过现有 Hybrid/RRF、Rerank、Parent Expansion 和 DashScope Chat 主链，返回非空 sources、真实 `source_url`、有效 `evidence_id`，回答内容与抓取政策正文一致。MinIO 抽查确认 crawler HTML Markdown 对象真实存在，临时 crawler 文件已清理。

### 当前限制

当前正常规模测试数据中仍保留本轮过滤规则修正前已写入的少量“名单”公告；修正后的规则会阻止后续同类标题进入，未对本地数据库和 Milvus 做猜测性批量删除。Crawler V1 暂不覆盖附件 PDF/DOC/DOCX 的本轮真实样本，后续需要真实页面出现对应高价值附件时再验证。

当前 hash 去重以文件内容为主；如果相同二进制附件被多个不同来源页引用，后续来源 provenance 可能无法完整保留。当前尚无真实阻断 Failure Case，因此暂不增加来源关系模型。

本轮没有进入 Version-aware Retrieval、Claim-Evidence Verification、定时采集、指定 URL API、前端 Crawler 按钮或其他下一阶段设计。

## 2026-09-19｜修复福大 CMS 政策附件采集

### 起因

上一轮正常规模抓取访问 34 个列表页，得到 149 个 HTML 文档，但 PDF、DOC、DOCX 均为 0。该结果与福州大学教务处真实详情页包含正式政策附件的事实矛盾，说明问题不在 MinIO、ETL 或 Milvus 主链，而在站点附件发现。

### 初始方案与根因

原实现只在 `.articelMain` 内扫描 `a[href]`，再从 URL 最后路径段或 anchor 文本推断文件名并按扩展名判断。对真实页面检查发现，正文位于 `.ny_box` 下的 `.articelMain`，附件则位于同一 `.ny_box` 下的兄弟容器 `.xl_main > ul > li > a`；两者不在同一个正文节点内。福大 CMS 的附件链接统一使用 `/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=...&wbfileid=...`，URL 本身没有 `.pdf`、`.doc` 或 `.docx` 扩展名，扩展名只出现在页面展示文件名中。因此原逻辑既没有遍历附件节点，也无法仅靠 download.jsp URL 识别文件类型。

### 新方案

保留 `.articelMain` 内普通链接兼容性，并在正文最近的 `.ny_box` 容器内额外发现 `a[href*='download.jsp']`。文件名优先取页面展示名称（anchor text，其次 title/download 属性），再回退到 URL 路径文件名；同一 `artifactUrl` 使用 URL 去重。PDF/DOC/DOCX 白名单和申请表、审批表、报名表、名单、模板等事务性附件黑名单保持不变。真实页面展示名称已包含扩展名，因此没有增加复杂的 `Content-Disposition` fallback；实际下载响应虽然也提供了 `Content-Disposition`，但本轮无需依赖它。

### 真实验证

- DOCX 详情页：`https://jwch.fzu.edu.cn/info/1036/14199.htm`，发现 `福州大学本科生转专业管理实施办法.docx`，artifact URL 为 `https://jwch.fzu.edu.cn/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=1744984858&wbfileid=16716630`；同页的 `福州大学参军退伍复学后学生转专业审批表.doc` 被黑名单跳过。
- PDF 详情页：`https://jwch.fzu.edu.cn/info/1036/14352.htm`，发现 `2025-2026学年各学院转专业实施细则.pdf`，artifact URL 为 `https://jwch.fzu.edu.cn/system/_content/download.jsp?urltype=news.DownloadAttachUrl&owner=1744984858&wbfileid=16732856`。
- 两类附件均通过 `FzuJwcCrawler → writeArtifact → DocumentIngestionService → MinIO → ETL → Milvus` 完成：数据库状态为 `COMPLETED`，对应 `etl_job` 为 `SUCCESS`；DOCX 产生 3 个 Parent Block、21 个 Milvus child，PDF 产生 23 个 Parent Block、100 个 Milvus child。
- MinIO 抽查确认 DOCX 对象大小 18,032 bytes、文件头为 ZIP `PK`，PDF 对象大小 1,503,627 bytes、文件头为 `%PDF`，没有把 HTML 错误页保存为附件。
- Milvus metadata 抽查包含 `doc_uuid`、`file_name`、`source_url`、`parent_block_id`、`evidence_id` 等字段；RAG 回归问题命中 PDF 附件 3 个 evidence source，并返回附件页码引用。

### 正常规模增量采集结果

修复后在不清空现有数据库的情况下再次执行默认规模 crawl：扫描 34 页，发现 626 条详情，相关 140 条，提交 22 个新 artifact，重复 150 个，跳过 520 个，失败 0；artifact 分类为 HTML 140、PDF 18、DOC 5、DOCX 9。已有 HTML 和重复附件由现有内容 hash 去重逻辑处理。

### 最终取舍与限制

本轮只做福州大学 CMS 当前真实 DOM 的最小兼容，没有引入通用 CMS 适配层、复杂 Content-Type 识别、WebMagic 或新的下载库，也没有调整 Embedding、Rerank、RRF、Chunk、Milvus schema 或检索算法。当前仍不支持 XLS/XLSX、ZIP/RAR、PPT/PPTX、图片和 OCR；扫描型 PDF 在 OCR 关闭时仍可能失败。相同二进制附件被多个来源页引用时，现有内容 hash 去重的 provenance 限制仍未处理。

## 2026-09-19｜修复 ETL 元数据与任务状态并完成存量 ACL 回填

### 目标与根因

本轮只处理存量 baseline 的状态可靠性和元数据一致性，不改变 Embedding、Rerank、Hybrid/RRF、Chunk、Milvus schema 或 RAG 主链。真实数据检查发现：ETL 父块文件名曾使用临时 ETL 文件名而不是业务文件名；`markRunning` 未清理旧的终态字段；成功任务可能残留 `next_retry_time`；ACL backfill 查询范围没有明确限制为 `COMPLETED` 文档。

运行时验证还暴露出两个 upstream 旁路问题：当前 MyBatis-Plus 只配置了分页拦截器，带 `@Version` 的 `updateById` 会生成但无法绑定 `MP_OPTLOCK_VERSION_ORIGINAL`；父块 JSON `tags` 在 UpdateWrapper 中直接绑定 `List<String>` 会被 MySQL 识别为 binary。两者都会阻断 ACL 回填的状态或父块元数据写回。

### 方案

- ETL 元数据统一优先使用 `knowledge_document.file_name`，只有业务文件名为空时才回退到临时路径文件名；父块和 child metadata 使用同一确定性文件名。
- `markRunning` 清理 `finished_at`、`next_retry_time`、`last_error`、`error_stack`，保留 retry history 和 active key；`markSuccess` 清理 retry schedule。
- ACL backfill 只选择 `COMPLETED` 且有 `doc_uuid/file_name` 的文档；ACL 管理器的文档和任务状态写回改为显式字段更新，避开未配置的乐观锁拦截器，不修改文档业务版本号。
- 父块 ACL 元数据同步时将 tags 序列化为 JSON 文本再写入 MySQL JSON 字段，保留现有父块查询、Parent-Child expansion 和检索流程。

### 数据清理与真实验证

在本地开发数据库 `campus_knowledge` 中，仅按已授权范围清理成功任务的旧 retry schedule：清理前 `SUCCESS AND next_retry_time IS NOT NULL` 为 170；执行 `UPDATE etl_job SET next_retry_time = NULL WHERE status='SUCCESS' AND next_retry_time IS NOT NULL`，更新 170 行；清理后为 0。

停止旧进程后执行 `mvn clean`、定向测试和重新编译，再用新编译产物启动应用。ACL backfill API 真实执行返回 `data=170`。执行后 MySQL 检查结果为：`knowledge_document` 为 `COMPLETED=170`、`FAILED=2`；`etl_job` 为 `SUCCESS=170`、`FAILED=2`；成功任务残留 retry schedule 为 0；完成文档与父块的 file_name mismatch 为 0；父块总数为 237；ACL refresh task 为 `SUCCESS=170`。

同时直接查询 Milvus collection `fzu_policy_rag_baseline_v1` 的 10 条 child 数据，确认返回 `doc_id/content/metadata`，metadata 含 `doc_uuid`、业务 `file_name`、`acl_version=1`、`parent_block_id`、`evidence_id` 和 `chunk_schema_version=2`。ACL 回填成功计数来自完整的 Milvus query → metadata rebuild → upsert → MySQL parent refresh 链路，不是只更新 MySQL。

### 测试与限制

新增/更新的定向测试共 19 项，全部通过；`mvn clean` 和 `mvn -DskipTests compile` 均通过。两个 FAILED 文档分别对应既有 OCR disabled 和 ETL timeout 数据，不在本轮 backfill 的 `COMPLETED` 选择范围内。全量测试中的外部 Milvus 连接问题和既有 architecture guard 仍按 upstream/环境问题处理，没有为此修改无关业务代码。

## 2026-09-19｜统一 DashScope 聊天模型标识并诊断 Embedding 网络抖动

### 起因与根因

前端聊天页仍将“Qwen 2.5”映射为 `modelId=ollama`，而当前后端主线已经使用 DashScope `qwen-flash`，导致前后端模型策略不一致。聊天失败日志中的 `DashScope embedding request failed: Connection reset` 发生在查询向量生成阶段；检索层将 Embedding 和 Milvus 异常统一包装为“知识库检索失败”，因此提示文本不能直接证明 Milvus 故障。

### 修改

- 用户聊天页和评测筛选页均只保留 `Qwen Flash`，前端请求统一发送 `modelId=qwen`。
- `modelNameFor` 对 `qwen` 和历史 `dashscope` 记录显示为 `Qwen Flash`，移除 Ollama、DeepSeek、Gemini 的模型选项和旧映射。
- `application.example.properties` 改为 DashScope Chat `qwen-flash`，保留 `qwen3.7-text-embedding-flash`、1024 维 Embedding 和 `qwen3.7-text-rerank` 配置。
- 前端重新构建，并同步更新 Spring Boot tracked static bundle；旧 bundle 中不再存在 `ollama` 或 `Qwen 2.5`。
- `application-ollama-openai.example.properties` 暂未删除，因为离线 `AblationStudyRunner` 和 `RagasDataExporter` 仍显式激活 `ollama-openai` profile；它不参与当前默认 DashScope 主链。

### 网络诊断与真实回归

本机到 `dashscope.aliyuncs.com:443` 的 TCP 连接成功；环境变量和 Windows 系统代理均配置为本地代理。PowerShell/curl 的 Schannel smoke 报 `SEC_E_NO_CREDENTIALS`，没有得到 HTTP 响应。使用 Java HTTPS 客户端分别直连和显式使用本地代理请求同一 Embedding 接口，两种方式均返回 HTTP 200，模型、1024 维、`dense&sparse` 和 `query` 参数均成功。结合此前 Java WebClient 的单次 `Connection reset`，当前证据支持临时网络/TLS/代理波动，不支持增加固定代理或修改 Java HTTP 客户端。

使用临时 18080 端口启动后端进行真实回归，前端请求语义对应的 payload 使用 `modelId=qwen`。`你是？` 请求成功返回无来源回答；“我是2024级本科生，现在申请转专业，应该按照哪一版规定？”请求成功返回非空 evidence sources 和政策回答，未出现 Retrieval、Embedding 或 reset 错误。没有修改 Java、Embedding、Rerank、Milvus 或检索算法。

## 2026-09-19｜增加 RAG 查询前处理与结果收口

### 方案

在 `mode=rag` 主链增加 QueryPreProcessor：精准问候和空白输入直接返回教务助手引导语，并通过现有 ChatModelStrategyFactory 与 ReactiveChatGateway 对带上下文的问题做查询重写。历史只取最近 4 条消息，Prompt 明确禁止新增未出现的学院、年份、身份、政策名称或条件。ChitChat 不经过 Retrieval 和 LLM，但复用 GroundedTurnModule 的 ChatMemory/ChatHistory 提交屏障保存本轮会话。

RetrievalPipeline 将 hybrid-topk 修正为 80，并在正常 Rerank 结果上应用 `rerank-score-threshold=0.30` 与 `final-child-topk=6`。Rerank 超时、熔断或异常返回无 `rerank_score` 的原始候选时保留 fallback 语义，仅做数量截断；阈值过滤为空时不查询 MySQL 父块，交由主链执行标准依据不足处理。

### 验证与限制

`mvn clean`、定向 12 个用例和 `mvn -DskipTests compile` 通过。全量测试 159 个用例中 157 个通过；`ReactiveRefactorGuardTest` 的既有 Agent/ETL `.block()` 守卫问题和未启动 Milvus 导致的 `MilvusSparseTest` `DEADLINE_EXCEEDED` 仍存在，均未为本次功能修改。

## 2026-09-19｜切换百炼 OpenAI-compatible Chat 适配层

### 现象与根因

Embedding、Milvus 和 Rerank 均可完成，但 Qwen Chat 使用 `DashScopeChatModel` 时，运行时却通过 `OpenAiChatOptions(response_format=json_object)` 请求结构化输出；两套适配层的请求契约不一致，导致 structured grounded answer 不能稳定解析。

### 方案

保留 DashScope native Embedding、Rerank 和 starter 提供的基础 Bean，将 `qwen` 策略切换为 Spring AI `OpenAiChatModel`。Chat 配置使用百炼 OpenAI-compatible endpoint，`base-url` 为 `https://dashscope.aliyuncs.com/compatible-mode`，由 Spring AI 1.1.2 默认补齐 `/v1/chat/completions`；DashScope Chat 自动配置明确关闭。删除不再使用的 `DashScopeChatModelStrategy`，保留前端 `modelId=qwen`、`OpenAiChatOptions`、structured parser、evidence 校验和 RAG 检索链路不变。

### 验证与当前限制

`mvn clean`、Chat 策略/Factory、`ReactiveChatGateway`、`ChatModelStrategy`、`GroundedTurnModule` 定向测试和 `mvn -DskipTests compile` 均通过。使用不含真实 Key 的临时配置启动 8081 端口时，Spring 上下文完成装配并监听端口；真实 DashScope 请求因占位凭据返回 401，未将其作为 Chat 功能结果。当前工具进程无法继承用户新 Key，因此真实 endpoint 抓取、structured raw JSON 和 10 次稳定性统计待使用有效本地环境变量重新启动后完成。本轮未重新加入任何代理配置，未修改 Embedding、Rerank、Milvus 或检索参数。

## 2026-09-19｜收口 RAG 聊天超时、流式缓冲与结构化回答

### 起因与方案

此前 DashScope OpenAI-compatible Chat 使用默认 HTTP 客户端，日志显示请求在约 10 秒读取超时；同时标准 RAG 虽然对外保持 SSE，但模型调用实际按一次性结果返回。现为 Chat 专用的 OpenAiApi 配置独立连接/读取超时，默认分别为 5 秒和 60 秒，继续复用 Spring AI 自动创建的 `openAiChatModel`，不改变 Embedding、Rerank 或 Milvus 客户端。

标准 RAG 的查询重写和 Qwen grounded answer 改为使用 `ChatClient.stream()`，在后端按顺序缓冲完整结果后再执行 JSON 解析、UsedSource 校验和会话持久化；外部 SSE 契约不变，不向前端暴露模型 token。Qwen grounded answer 使用严格 JSON Schema（`answer`、`answerType`、`usedSources`），Agent/其他模型仍保留原有工具调用和兼容解析路径。

查询重写只取最近 4 条历史消息，并将超时、异常或空结果统一 fail-open 到规范化原问题；异常日志记录 conversationId、modelId、异常类型和 fallback tracing 标签，不吞掉后续检索异常。

### 验证与当前限制

新增 Chat HTTP 配置、结构化解析、流式片段顺序和 Query Rewrite fallback 测试。定向 34 个测试全部通过；`mvn clean compile` 通过。定向测试在本机 JDK 21 下需要 Surefire 启用动态 agent 参数，这是 Mockito 测试运行环境要求，未修改项目生产配置。

真实 smoke test 首次使用旧 PowerShell 进程继承的旧凭据，Embedding 和 Chat 均返回 DashScope `401 invalid_api_key`；核对环境变量长度后确认该进程值与 Machine 级新值不同。临时启动新 JVM 并显式使用 Machine 级环境变量后，Embedding、Milvus、Rerank 和 Qwen Chat 均返回成功。无历史政策问题、带历史追问和 ChitChat 均完成验证；ChitChat 返回固定引导文本且不触发 Embedding/Rerank，正常回答返回非空 evidence。主回答通过 `ChatClient.stream()` 获取模型结果并在后端缓冲后校验，外部仍保持 SSE；会话标题服务仍有独立的非流式标题请求，不属于 RAG 回答主链。查询重写异常/超时的 fail-open 由定向测试覆盖。未记录或提交任何真实 API Key。

## 2026-09-20｜补齐 RAG 阶段可观测性与 FAQ 安全快路径

### 设计与边界

本轮将 strict grounded answer 的结构非法结果统一归类为 `StructuredAnswerException`，只在 `GroundedTurnModule` 中映射为 `SourceValidationException(json_parse_failed)`；普通网络、超时、检索和数据库异常不再依赖异常文本判断，也不会被误报为证据不足。

标准 `mode=rag` 增加以 `traceId`、`conversationId`、`msgId`、`modelId` 关联的低噪音阶段日志和 tracing：start、preprocess、hybrid、rerank、parent/context、generate、validate、persist、completed，以及对应的失败/fallback 状态。日志只记录字符数、候选数量、阈值、耗时和 answerType 等摘要，不记录完整 Query、Prompt、Context、回答或 Authorization/API Key。`rag.llm.log-raw-response` 默认保持关闭。

在 Exact ChitChat 与历史 Query Rewrite 之间加入人工维护 FAQ 的 Dense cosine fast-path。FAQ 使用 `rag/faq.json` 的小型资源文件和 DashScope Dense embedding，默认阈值为 `0.95`、Top1/Top2 margin 为 `0.03`；当前模板为空，避免凭空固化未经审核的政策答案。FAQ 初始化、embedding 或相似度异常均 fail-open 回到 ORIGINAL/REWRITE；明显上下文依赖的追问不参与 FAQ 匹配。命中 FAQ 时跳过 Retrieval、Rerank、Parent Expansion 和最终 LLM，但复用现有 ChatMemory/ChatHistory 持久化，sources 为空。

### 验证

`mvn clean compile` 通过；本轮定向测试 43 个全部通过，覆盖结构化异常边界、FAQ threshold/margin/fail-open、路由顺序、FAQ 持久化和既有 Retrieval 回归。全量测试 176 个中 174 个通过，剩余 `ReactiveRefactorGuardTest` 的既有 Agent/ETL `.block()` 守卫问题及本机 Milvus 未连接导致的 `DEADLINE_EXCEEDED` 与本轮无关。本轮未实现浏览器端 Token Streaming，未修改 SSE 协议、Retrieval/Rerank/Parent-Child 算法、Embedding 模型或 Chat HTTP timeout。

## 2026-09-20｜建立先校验来源再输出的 Qwen 真流式链路

### 设计与边界

标准 Qwen `mode=rag` 从原来的“模型流式请求但后端完整缓冲 JSON”改为两阶段生成。Phase A 使用 strict JSON Schema 只规划 `answerType` 和 `usedSources`，输入仅包含最终 child candidates；规划结果先经过 `UsedSourceValidator` 的候选 evidence 白名单校验，再按已验证 evidence_id 收口 Parent Context。只有校验通过后才启动 Phase B 的普通文本 `ChatClient.stream()`，因此任何 factual 文本 chunk 暴露前都已经完成引用校验。

Phase B 使用 request-local buffer 累积完整回答，同时逐 chunk 向下游发送；模型正常完成后才执行 ChatMemory/ChatHistory 持久化，持久化完成后 SSE 才发送 `done`。取消或中途异常不会持久化不完整答案；已经发送过 chunk 后发生异常只发送 `error` 和 `done`，不再拼接误导性的 fallback message。前端沿用既有 `message` 事件追加逻辑，仅保持已出现部分答案在 `error` 后不被 `done` 状态覆盖。

Qwen strategy 显式声明支持 validated streaming；DeepSeek 保留原有 Function Calling、完整校验和一次性结果路径。ChitChat、FAQ、Query Rewrite、Retrieval、Rerank、Parent-Child 和 SSE 事件名称均未重构。

### 验证与当前限制

新增 Source Plan strict decode、SourcePlan evidence 校验、Qwen 多 chunk、完成后持久化、refusal 短路、mid-stream error、SSE 顺序和 DeepSeek 兼容回归测试。定向测试全部通过；`mvn clean compile` 通过；全量测试 187 个中仅剩既有 `ReactiveRefactorGuardTest` 的 Agent/ETL `.block()` 守卫失败，Milvus 定向测试已通过且不再报错。`npm run build` 在正常权限环境通过，仅保留依赖注释和 chunk size 警告。本轮没有把 structured JSON 直接透传给浏览器，也没有修改前端 SSE 协议或 Retrieval/Rerank 算法。

## 2026-09-20｜增加语义会话路由与真实 FAQ 快速路径

### 设计与边界

在标准 `mode=rag` 的检索前增加保守输入归一化和一次 Structured Output 会话路由。归一化只处理首尾空白及连续的末尾问号、感叹号、句号和波浪号，不做子串命中。精确问候、身份和能力别名走确定性直返；其余输入由 `ConversationIntentRouter` 在最近 4 条 User/Assistant 消息范围内选择 `SMALLTALK`、`CLARIFY` 或 `RETRIEVE`，其中 `RETRIEVE` 同时产出上下文消解后的检索 Query。路由模型调用、JSON 解析失败、超时和网络异常均 fail-open 到既有 FAQ/查询重写/RAG 路径，不改变 Retrieval、Rerank、Parent-Child、Source Plan、Evidence Validation 或 Qwen 真流式回答。

FAQ 资源从本地 MinIO 实际对象 `documents/e97ebdae13854431958c611f57bc6b9c/fzu-undergraduate-major-transfer-policy.txt` 读取并核对后，整理为 7 条转专业 FAQ，覆盖办理频次、基本条件、人数比例、特殊限制、不得转专业情形、办理流程和 2024 级生效范围。每条保留 MinIO object、文件名和条款位置审计信息；阈值仍为 `0.95`，Top1/Top2 margin 仍为 `0.03`。FAQ 命中跳过 Milvus 和最终 LLM，但沿用现有直接回复持久化能力。

### 验证与当前限制

`mvn clean compile` 通过；路由、归一化、FAQ matcher、ChatService 和严格 JSON 路由解析定向测试通过。全量测试 193 个中 192 个通过，唯一失败仍为既有 `ReactiveRefactorGuardTest` 扫描到 Agent/ETL 的 `.block()`。本轮未修改这些既有代码。MilvusSparseTest 本次已通过。

后端使用临时 18080 端口启动成功，DashScope Embedding warmup 返回 HTTP 200，Milvus schema 初始化成功；真实聊天请求因本机 MySQL `root` 凭据不匹配而无法登录获取 JWT，未伪造认证或修改数据库配置。未记录或提交任何 API Key、Token 或本地 secret。

## 2026-09-20｜补充用户 Query 与重写 Query 可观测性

### 变更

RAG 请求入口日志补充用户输入，QueryPreProcessor 日志补充原始 Query 与实际检索 Query，便于核对语义路由、FAQ 匹配和上下文重写是否符合预期。日志文本会压平换行、限制长度，并对常见 API Key、Token、Password、Secret 和 Authorization 形式做脱敏；不记录完整 Prompt、Context 或模型原始回答。

### 验证

仅增加日志字段和日志文本处理，不改变检索、重排、引用校验、回答生成或 SSE 行为。定向聊天/来源校验测试在受限 JVM 内存参数下通过。

## 2026-09-20｜调整回答结构与追问风格

最终回答提示词要求先用一句话给出结论，再说明政策依据、适用条件和办理要点；当用户问题缺少必要个人信息时，先回答当前可确定的部分，再在结尾询问必要的补充信息。该调整只影响回答表达，不放宽知识库证据约束、不改变引用校验和 SSE 链路。

补充修复会话路由器内部角色泄露：路由提示词明确要求 `directReply` 使用最终助手身份，后端对“路由器/分类器/Prompt”等内部表述做用户侧兜底替换，并扩充“你是谁啊”等确定性身份问候匹配。
