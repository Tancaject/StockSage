# Contextual Retrieval（上下文化检索）设计

- 日期：2026-06-09
- 状态：已实现（开关默认关闭）；待用户重入库后执行 §3.6 A/B 评测
- 范围：仅 EDGAR（SEC 10-K / 10-Q）证据层的入库与检索；不动行情/新闻/多市场路由。

## 1. 背景与动机

当前 RAG 检索链路（`RagService.retrieveForEval`）：

```
查询改写 → ticker 元数据过滤 → 向量召回(Milvus) → 关键词召回(MySQL FULLTEXT) → RRF 融合 → gte-rerank top-5 → 父块扩展
```

检索的真正瓶颈是**召回**：子块（800 字符）语义自足度不够，"the Company"/"such risks"/"this segment"
这类指代在脱离上下文后无法被精确召回。Anthropic 的 Contextual Retrieval 用一句 LLM 生成的
"情境说明"在入库前 prepend 到每个分块，使分块的向量表示与关键词表示自带上下文，实测可把
失败召回率下降 49%（叠加 rerank 后 67%）。

### 已经具备的能力（v3 基线，不要当成新工作）

核对源码后确认，下面这些**已经做了**，不在本次范围：

- **结构化前缀**：`EdgarIngestionService.buildChildEmbeddingText`（286–308 行）在 embedding 前已给
  子块拼了 `Company / Filing / Filing date / Section` 前缀。
- 该前缀文本即子块 Document 的正文，`KnowledgeIngestionService.toVectorDocument`（284–296 行）
  把它整段存进 `content_full`，所以 **FULLTEXT 关键词腿也已经索引了带前缀的文本**。
- 父子分块、RRF（`rrf-k=60`）、gte-rerank top-5、ticker 过滤均已就绪。

因此本设计的**唯一核心增量**是：在结构化前缀之外，再加一段 **LLM 生成的语义 gist**，
并保证两条检索腿（向量 + FULLTEXT）都吃到它。

### 明确不做（YAGNI / 守住既有定位）

- **不引入新的 BM25 引擎**（Elasticsearch / Lucene）。结论依据：Contextual Retrieval 的增益主要来自
  "索引什么文本"，而非"用哪种打分器"；MySQL `MATCH...AGAINST` 属 BM25 家族，够用。新引擎违背
  本项目刻意的轻基建定位（即对 FinSight 过度工程的判断）。
- 不替换父子分块。gist 改善"子块能否被召回"，父块扩展改善"回答时给 LLM 的完整上下文"，互补。
- 不动多市场路由、多空辩论、行情/新闻链路。

## 2. 目标与成功标准

- 用 50 题金标准评测集（`rag-eval/run_rag_eval.py` → `POST /api/eval/rag`）做 v3 基线 vs v4 上下文化的 A/B。
- **保留 v4 的硬条件**：`context_recall`、`MRR`、`nDCG` 至少一项显著上升，且其余指标不出现明显回退。
  若不达标，保留开关默认关闭，作为可复现的负面结论记录（本身也是面试故事）。
- 入库可重入：重复入库不重复付 LLM 费用（gist 按内容哈希缓存）。
- 失败开放（fail-open）：单个分块 gist 生成失败时，回退到仅结构化前缀，不阻断整篇入库。

## 3. 设计

### 3.1 组件划分

| 组件 | 职责 | 依赖 |
|---|---|---|
| `ContextualEnricher`（新增 `rag` 包） | 给定 (父块文本, 子块文本, 结构化元数据) 生成 1 句 gist | FAST 档 ChatModel、`ContextualGistCache` |
| `ContextualGistCache`（新增） | 按 `sha256(childText)` 缓存 gist，重入幂等 | MySQL 表 `contextual_gist_cache` |
| `EdgarIngestionService`（改） | 在生成子块时调用 enricher，把 gist 注入 embedding 文本与 metadata | `ContextualEnricher` |
| `KeywordSearchService`（小改） | 处理 FULLTEXT 50% 阈值 / 最小词长，避免上下文前缀稀释关键词 | — |
| 配置（改） | 新开关与 v4 版本号 | — |

### 3.2 LLM gist 生成

- **粒度**：默认 `child`（逐子块，最贴近 Anthropic 原方法）。另提供 `parent` 廉价档（每父块一次、
  其子块共享该 gist）。开关：`stocksage.rag.contextual.granularity`。整体启停由 `contextual.enabled`
  这一个主开关控制（关闭即回退 v3 行为），不再用 `granularity=off` 重复表达。
- **上下文输入**：用**父块**（≤3000 字符）作为情境，而非整篇 10-K——成本远低，且不依赖 prompt cache。
- **模型**：FAST 档（`Coordinator` 的 FAST 路由同源），低成本、可批量。
- **提示词**（要点）：给出公司/章节/财年 + 父块 + 子块，要求输出**一句**、≤40 词的中文情境说明，
  解释"这段在该 10-K 中讲什么、属于哪个论点/指标"，**不得复述原文、不得编造数字**。
- **产物落地**：gist 存入子块 `metadata.contextual_gist`；并把它拼进 `buildChildEmbeddingText` 的前缀，
  位置在 `Section:` 之后、正文之前。这样向量腿与 `content_full`（FULLTEXT 腿）同时吃到。

子块 embedding 文本（v4）形态：

```
Company: Apple Inc. (AAPL)
Filing: 10-K
Filing date: 2023-11-03
Section: Item 1A Risk Factors
Context: 本段量化 FY2023 大中华区营收下滑并归因于汇率逆风，延续上一段的地域集中度风险论述。
<原始子块正文>
```

### 3.3 幂等与成本控制

- `ContextualGistCache`：表结构 `child_text_hash (PK, char(64))`、`gist (text)`、`model (varchar)`、
  `granularity (varchar)`、`created_at`。生成前先查缓存命中即复用。
- 现有来源级哈希跳过（`docIndexRepository.findByFilePath` + `fileHash`）仍生效；gist 缓存是其下一层，
  保证即便来源哈希因 v4 版本号变化而失效、需要重切，也不会重复调用 LLM 处理未变化的子块文本。
- gist 失败：记录 warn，metadata 不写 `contextual_gist`，前缀退回 v3 结构化形态。整篇入库不中断。

### 3.4 关键词腿（FULLTEXT）调优

- `content_full` 现已含 `Company:/Filing:/Section:` 等标签词，这些词在几乎所有 EDGAR 行中出现，
  NATURAL LANGUAGE MODE 的 50% 阈值会自动将其归零——属期望行为（它们是噪声），无需特殊处理。
- 真正风险是有区分度的 gist 词被 50% 阈值或 `innodb_ft_min_token_size`（默认 3）误伤。应对（按需，二选一）：
  1. 把关键词查询在命中过少时回退到 `IN BOOLEAN MODE`；
  2. 评测若显示短词召回不足，再调低 `innodb_ft_min_token_size` 并重建 FULLTEXT 索引。
- 本项默认**不改**，仅在 3.6 评测出现关键词腿回退时启用，避免过早优化。

### 3.5 版本号与重新入库

- `stocksage.rag.edgar.chunking-version` 升级到 `edgar-v4-contextual-gist-child-vector`。
- `buildContentHash` 已包含 chunkingVersion，版本号变化即触发整源重切重入。
- 全量重跑 EDGAR 入库一次（**由用户执行**，本项目约定不代跑入库脚本）。v4 标签确保新旧行不混。

### 3.6 验证（A/B 闸门）

1. 关闭开关（`contextual.enabled=false`）跑一遍 50 题评测 = v3 基线，留档。
2. 打开开关、`granularity=child`，重新入库后跑同一评测 = v4。
3. 对比 `context_recall / MRR / nDCG / citation_precision/recall`。
4. 达标（3.2 硬条件）则默认开启；否则保留开关默认关闭并记录负面结论。
5. 可选第三组：`granularity=parent` 廉价档，看性价比。

## 4. 配置项（新增）

```properties
stocksage.rag.contextual.enabled=${STOCKSAGE_RAG_CONTEXTUAL_ENABLED:false}
stocksage.rag.contextual.granularity=${STOCKSAGE_RAG_CONTEXTUAL_GRANULARITY:child}   # child | parent
stocksage.rag.contextual.max-gist-words=${STOCKSAGE_RAG_CONTEXTUAL_MAX_GIST_WORDS:40}
stocksage.rag.edgar.chunking-version=${STOCKSAGE_RAG_EDGAR_CHUNKING_VERSION:edgar-v4-contextual-gist-child-vector}
```

默认 `enabled=false`：合并代码不改变现网行为，开关 + 重新入库后才生效，符合"先基线后对照"的验证顺序。

## 5. 测试

- `ContextualGistCacheTest`：命中复用、未命中生成、按 hash 幂等。
- `ContextualEnricherTest`：mock ChatModel，验证前缀拼接位置、失败 fail-open 回退、`off` 直通。
- `EdgarIngestionServiceTest`：开关开/关下子块 embedding 文本是否含/不含 `Context:` 段；父子粒度行为。
- `KeywordSearchService`：若启用 3.4 调优，补 BOOLEAN 回退用例。
- 评测脚本不算单测，作为 3.6 的人工闸门。

## 6. 风险与残留

- **LLM 成本/耗时**：逐子块调用量大（粗估 20 公司 × 数份 × 数百子块）。缓解：父块作上下文（输入小）、
  gist 缓存幂等、提供 `parent` 廉价档。入库由用户手动批量执行，可接受。
- **gist 质量**：劣质 gist 可能引入噪声反伤召回——正是 3.6 A/B 闸门要拦截的；不达标不上线。
- **指代消解依赖父块**：若某子块的关键上下文在父块之外（跨父块），gist 可能仍不足。范围内接受。
