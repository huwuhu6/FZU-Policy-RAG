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
