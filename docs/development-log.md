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
