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
