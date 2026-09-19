# FZU-Policy-RAG 项目开发规范

## 项目定位

项目名：`FZU-Policy-RAG`

项目定位：

面向福州大学教务政策与通知的可靠 RAG 系统。基于成熟开源 Hybrid RAG baseline 二次开发，重点解决持续更新政策场景中的版本有效性、适用范围和回答证据可靠性问题。

当前核心技术方向：

- Java 17
- Spring Boot
- Spring AI / Spring AI Alibaba
- MySQL
- Redis
- Milvus
- Dense + Sparse Hybrid Retrieval
- RRF
- Rerank
- Parent-Child Retrieval
- Citation / Evidence
- 阿里云百炼 / DashScope

## 开发原则

1. 优先复用已经验证的 baseline 能力，不重复实现成熟的标准 RAG 组件。
2. 不为了“架构完整”“技术新颖”或简历包装主动引入复杂模块。新增设计必须对应真实业务问题、真实 Failure Case 或明确的工程约束。
3. 避免过度设计。能够通过简单、明确、可测试的实现解决的问题，不引入复杂状态机、多 Agent、额外中间层或无必要抽象。
4. 修改现有 RAG 主链前，先确认影响范围。不得因为局部问题无理由重写：
   - ETL
   - Parent-Child Chunk
   - Hybrid Retrieval
   - Milvus
   - RRF
   - Rerank
   - Parent Expansion
   - Citation / Evidence
5. 当前所有模型能力原则上通过阿里云百炼 / DashScope API 使用，不引入新的本地模型运行环境，例如 Ollama、TEI、本地 BGE 服务，除非用户明确要求。
6. API Key、密码、Token 等真实 Secret 禁止提交到仓库。优先使用环境变量，例如 `DASHSCOPE_API_KEY`。
7. 本地专用配置、生成文件、IDE 文件、运行数据不得随意进入 Git。
8. 修改完成后执行与修改范围匹配的最小必要验证。不要为了让无关 upstream 集成测试通过而修改业务代码。
9. 如果测试失败由未启动的外部基础设施导致，应明确记录原因，不允许通过 fake implementation、删除测试或弱化断言来制造“测试通过”。
10. 不修改与当前任务无关的代码，不顺手进行大规模格式化、重命名或 package 重构。

## 当前业务开发优先级

以下是规划方向，本文件本身不要求在当前任务中实现这些功能：

1. 跑通 baseline
   - 百炼 Embedding
   - 百炼 Rerank
   - DashScope Chat
   - MySQL / Redis / Milvus
   - 真实福大文档 ETL + QA
2. 福州大学教务处 Jsoup 增量采集
3. Version / Validity-aware Retrieval
   - 解决新旧政策版本冲突
   - effective time
   - applicable cohort
   - supersedes / validity
4. Claim-Evidence Verification
   - Claim decomposition
   - Claim ↔ Evidence verification
   - SUPPORTED / PARTIAL / UNSUPPORTED

Jsoup crawler 属于知识更新基础能力，不应为了包装成核心 AI 亮点而过度设计。

Version/Validity-aware Retrieval 和 Claim-Evidence Verification 是当前最主要的业务增强方向，但必须建立在 baseline 已经跑通和真实 Failure Case 之上。

## 开发记录

- 完成对项目行为、架构、业务能力或测评有意义的工作单元后，检查是否需要更新 `docs/development-log.md`。
- 开发记录使用中文，记录设计背景、方案取舍、真实问题、验证结果和当前限制，不记录普通语法错误、编译错误或格式化过程。
- 真实踩坑必须记录方案演进和验证结果；重要测评必须记录背景、baseline、数据、指标、结果和结论。
- 不允许虚构失败、结果、指标或历史过程；不要求每个 commit 都增加一条开发记录。

## Git 规则

### Commit Message

所有 Git commit message 使用：

```text
<type>: <中文描述>
```

`type` 使用常见 Conventional Commit 前缀，例如：

```text
feat:
fix:
refactor:
perf:
test:
docs:
chore:
build:
ci:
```

`type` 前缀保持英文，冒号后的提交说明必须使用中文。

正确示例：

```text
feat: 增加政策版本有效性过滤
fix: 修复父块引用信息丢失问题
refactor: 解耦百炼嵌入模型客户端
test: 增加版本冲突检索回归用例
chore: 清理未使用的本地模型配置
docs: 补充知识摄取流程说明
```

不要使用：

```text
feat: add version aware retrieval
fix: fix retrieval bug
update code
修改代码
```

Commit message 应描述本次提交真正完成的逻辑变更，不要使用模糊描述。

### 自主提交权限

Codex 在完成一个边界清晰、已经验证的工作单元后，可以自行执行 `git add` 和 `git commit`，无需每次询问用户是否提交。

但是：

- commit 前必须先检查 `git diff` / `git status`。
- 不得提交真实 Secret。
- 不得夹带与当前任务无关的修改。
- 不得因为工作区存在用户其他修改而覆盖、重置或删除这些修改。
- 不得使用 `git add -f` 绕过 `.gitignore`；应正确修复忽略规则。
- commit 后应报告 commit hash、提交说明和验证结果。

### 验证与失败处理

- 优先运行与修改范围直接相关的定向测试，再根据风险运行更大范围的验证。
- 外部服务未启动、网络不可用或本地依赖缺失导致的失败，应记录为环境问题。
- 不得为了消除无关失败而修改业务实现、删除测试、降低断言或添加 fake/dummy fallback。
