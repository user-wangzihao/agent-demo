# AgentDemo 评估 CI（eval-tools）

> AgentDemo 项目的离线评估工具集。建立 RAG 系统的可量化质量基线、提供回归保护、为后续"评估驱动调优"奠定数据基础。

---

## 1. 概述

### 1.1 这个模块解决什么问题

AgentDemo 是一个 Multi-Agent RAG 系统。随着 Graph 拓扑演化（从单 Agent 到 11 节点 + 2 ConditionalEdge + 1 并行 fan-out/fan-in）、检索 pipeline 不断重构（三层 fallback、Doc + FAQ 双 collection 合并、Rerank 加入），传统的"开发者凭手感判断好坏"已经无法支撑：

- **改了一个 prompt，怎么知道是变好了还是变差了？**
- **新增 feature 加权策略，对未受影响的 query 是否有副作用？**
- **流式响应改进后，首字时延究竟降低了多少？**

这些问题需要**可量化、可回归、可对比**的评估机制。本模块就是为此而生。

### 1.2 在 AgentDemo 项目里的定位

eval-tools 是项目结构中的一个**独立离线评估工具模块**，物理上放在 agentdemo 根目录下仅为 IDE 便利，**不参与运行时**，不被任何主应用代码引用。包名 `com.wzh.agentdemo.evaltools.*` 与主应用 `com.wzh.*` 完全隔离。

```
agentdemo/                       ← 项目根目录
├── agent-demo-common/           ← 公共模块 (entity / mapper)
├── agent-demo-main/             ← 主应用 (Spring Boot, port 9999)
├── agent-demo-mcp-server/       ← MCP Server (port 9527)
└── eval-tools/                  ← 本模块, 独立 jar, 不进 Spring 容器
```

运行时通过 HTTP（调主应用 internal 端点）+ 直连基础设施（Milvus / DashScope）的方式调用被评估系统，**编译期完全解耦**。

### 1.3 与既有 GroundTruthAuditor 的关系

eval-tools 内部其实有**两套主类并存**：

| 主类 | 引入时机 | 用途 | 默认入口 |
|---|---|---|---|
| `GroundTruthAuditor` | 早期 | 一次性脚本，用于在系统冷启动时审计 ground truth 标注遗漏 | pom shade 默认入口 |
| `EvalRunner` | 评估 CI Batch 1 | 统一的多任务评估编排器，本 README 主要描述对象 | 通过 `-Dexec.mainClass` 指定 |

两套主类**互不干扰、零代码冲突**。EvalRunner 引入时严格遵循"既有代码零改动"原则，GroundTruthAuditor / AuditReporter / milvus / llm 子包全部未触碰。

---

## 2. 评估维度全景

四个评估任务，每个任务**独立可运行**，也可一次性全跑。

| Task | 评估什么 | 数据源 | 主要指标 | 调用路径 |
|---|---|---|---|---|
| `intent` | 意图分类准确率 | `eval-set-intent.txt` (21 case) | accuracy + 分类来源分布 | HTTP → `/internal/eval/intent` |
| `route` | Graph 路由正确率（纯函数） | `eval-set-intent.txt` 共用 | accuracy（按意图分桶） | 纯本地函数复制 RouteUtil |
| `retrieval` | RAG 向量检索质量 | `eval-set.txt` (24 case) | MRR@5 / NDCG@5 / Recall@5 | DashScope embed + 直连 Milvus |
| `latency` | 端到端延迟 | `eval-set.txt` (24 case) | first_token / total 的 P50 / P95 | HTTP → `/api/graph/chat-stream` SSE |

### 2.1 当前基线（2026-05-20）

> 以下数字为 eval-tools 主线建立后的**首次基线**。后续优化均以此为对照参考。

**意图分类**
```
accuracy          = 80.95%  (17/21)
keyword 来源占比   = 52.4%
LLM 来源占比       = 28.6%
fallback 占比      = 19.0%
平均分类延迟       = 209ms
```

**路由正确率**
```
accuracy = 95.24%  (20/21)
```

**检索质量**
```
mrr@5      = 0.9028     ← top-K 内首次命中相关 chunk 的 rank 倒数
ndcg@5     = 0.8356     ← 排序质量（二值相关性）
recall@5   = 0.3224     ← 上限受 expected 集合规模 (7~36) 物理约束
hit_rate@5 = 1.0000     ← 24/24 全部 top5 至少命中 1 个相关 chunk
mrr@10     = 0.9028     ← 与 K=5 相等, 说明所有命中都在 top5 内
ndcg@10    = 0.7929
recall@10  = 0.5304
```

**端到端延迟**
```
first_token P50 = 1485ms     first_token P95 = 3194ms
total       P50 = 6688ms     total       P95 = 8477ms
total min = 4396ms           total max = 8903ms
P95 / P50 倍率 = 2.15x       ← 健康范围 (>5x 才算长尾失控)
```

完整跑分报告：`rag-eval-output/eval-report-{timestamp}.md`。

---

## 3. 快速开始

### 3.1 环境前提

| 依赖 | 版本/状态 |
|---|---|
| JDK | 21+ |
| Maven | 3.8+ |
| 主应用 AgentDemo | 已启动在 http://localhost:9999（intent / latency 任务必需） |
| Milvus | 可达 10.82.13.61:19530（retrieval 任务必需） |
| DashScope API Key | 已配置在 `AuditConfig.DASHSCOPE_API_KEY`（retrieval / GroundTruthAuditor 必需） |
| 测试账号 | sys_user 表中存在 username=user / password=user123 / role=user（latency 任务必需） |

### 3.2 一键全跑

```bash
cd eval-tools
mvn clean package
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.EvalRunner \
              -Dexec.args="--task=all"
```

预计耗时：意图 ~5s + 路由 <1s + 检索 ~15s + 延迟 ~8min（24 case × 3 轮）≈ **约 9 分钟**。

### 3.3 单任务运行

```bash
# 仅意图分类
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.EvalRunner \
              -Dexec.args="--task=intent"

# 仅检索质量
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.EvalRunner \
              -Dexec.args="--task=retrieval"

# 仅端到端延迟
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.EvalRunner \
              -Dexec.args="--task=latency"

# 仅路由（无网络依赖, 跑得最快）
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.EvalRunner \
              -Dexec.args="--task=route"
```

### 3.4 报告输出

跑完后看 `rag-eval-output/eval-report-{yyyyMMdd-HHmmss}.md`，包含四个任务的：
- 状态分布总览表
- 每个任务的指标明细 + 失败 case 列表（前 20 条）
- 总耗时与通过率

---

## 4. 任务详解

### 4.1 意图分类准确率（`intent`）

#### 评估什么

`HybridIntentClassifier` 是 AgentDemo 的混合意图分类器（关键词优先 + LLM 兜底）。本任务验证它在 21 条人工标注 query 上的分类准确率，并暴露分类来源分布。

#### Ground Truth

`src/main/resources/eval-set-intent.txt`，21 条 case，覆盖 4 大类：

| 大类 | 数量 | 期望 intent |
|---|---|---|
| 闲聊类 | 6 | chitchat |
| 管理员指令类 | 8 | admin_command |
| 工单类 | 4 | default (后续 RouteUtil 转 ticket_agent) |
| 兜底类 | 3 | default |

格式示例：
```
[闲聊类]
query: 你好啊
expectedIntent: chitchat
```

#### 调用架构

```
EvalRunner
    │
    ▼
IntentEvalTask
    │
    ▼ HTTP POST {query}
EvalHttpClient ──────────────────────────┐
                                          │
                                          ▼
                              主应用 /internal/eval/intent
                                          │
                                          ▼
                              InternalEvalController
                                          │
                                          ▼
                              HybridIntentClassifier.classify(query)
                                          │
                                          ▼
                              返回 {intent, source, confidence, reasoning, elapsedMs}
```

**为什么走 HTTP 不直接调用分类器？** 评估器与主应用编译期解耦，主应用代码改动不影响评估工具。HTTP 的成本在意图分类这种秒级任务里完全可接受。

#### 指标语义

| 指标 | 含义 |
|---|---|
| `accuracy` | 整体准确率 |
| `accuracy[大类]` | 按 4 大类分桶的准确率，用于定位"哪一类被错分得最多" |
| `source[KEYWORD\|LLM\|FALLBACK]` | 分类来源分布，反映关键词字典的覆盖度 |
| `avg_classifier_latency_ms` | 平均分类延迟 |

#### 当前数字（2026-05-20）

- **accuracy = 80.95%**（17/21）
- KEYWORD 命中 52.4%、LLM 兜底 28.6%、FALLBACK 19.0%

#### 已知问题（4 条）

> 这些 bug 是 Batch 3 / Batch 4 评估发现的真实问题，**当前选择暂不修**，留作"评估驱动调优"一刀的演示素材。

| # | query | expected | actual | 来源 | 根因 |
|---|---|---|---|---|---|
| 1 | 今天天气怎么样 | chitchat | how_to | KEYWORD | 关键词字典里 "怎么" 触发了 how_to 分支，与 chitchat 撞车 |
| 2 | 我的工单 TK-... 现在什么状态 | default | admin_command | LLM | LLM prompt 对工单查询 vs 管理员指令的边界描述不清 |
| 3 | 苹果好吃还是橘子好吃 | default | chitchat | LLM | LLM 倾向把"轻松对比"识别为闲聊（标注合理性也值得讨论） |
| 4 | 帮我写一首关于春天的诗 | default | chitchat | LLM | 同上，创作任务被识别为闲聊 |

---

### 4.2 路由正确率（`route`）

#### 评估什么

主应用的 Graph 路由分流逻辑（`RouteUtil` + `MainGraphConfig` 的两个 ConditionalEdge）。给定 `(expectedIntent, role)`，验证路由层会把请求转向哪个节点（chitchat_answer / feature_resolve / admin_agent / ticket_agent / knowledge_answer 之一）。

#### 调用架构

**纯函数复制策略**——`RouteSimulator` 是 `RouteUtil` + `MainGraphConfig` 路由分流逻辑的纯函数副本，**无任何 HTTP 调用**。

```
EvalRunner
    │
    ▼
RouteEvalTask
    │
    ▼ (intent, role) → routeDecision
RouteSimulator (本地纯函数, 0 HTTP)
    │
    ▼
返回 {chitchat_answer / feature_resolve / admin_agent / ticket_agent / knowledge_answer}
```

**为什么纯函数复制而非 HTTP？** 路由逻辑是**纯条件判断**，没有副作用、没有 LLM 调用、没有 DB 查询。复制成本极低（约 50 行代码），收益是评估速度从秒级降到毫秒级、且永远不依赖主应用启动。

**同步纪律**：`RouteSimulator` 的注释顶部有一份 checklist，重构 `RouteUtil` 时必须同步更新副本，否则评估失真。

#### 指标语义

- 输入用 `expectedIntent`（而非实际意图分类结果），独立可观测，与意图分类任务的成败解耦
- accuracy 按 4 大类分桶 + 按路由目标分桶双视角输出

#### 当前数字（2026-05-20）

- **accuracy = 95.24%**（20/21）

#### 已知问题（1 条）

| # | query | expected | actual | 根因 |
|---|---|---|---|---|
| 1 | 联系一下客服 | ticket_agent | knowledge_answer | `TICKET_PATTERN` 正则不容忍"联系"和"客服"之间的字符 |

#### 主要价值

此任务**无网络依赖**，跑得最快（毫秒级）。主要价值是**回归保护**——未来重构 `RouteUtil` / `MainGraphConfig` 时可保证既有规则不破。

---

### 4.3 检索质量（`retrieval`）

#### 评估什么

RAG 向量检索的**底层质量基线**——给定一个用户 query，向量库 top-K 召回的相关 chunk 排序质量。**仅评估裸向量检索**，不含 feature_name 过滤、不含 rerank、不含 RRF 融合。

#### Ground Truth

`src/main/resources/eval-set.txt`，24 条 case，每条标注 7~36 个"相关 chunk"（采用宽松集合标注语义）。Ground truth 通过半自动 LLM-as-Judge 工具 `EvalSetRebuilder` 构建（详见第 5 节）。

格式示例：
```
问题类

赋注解属性工具
问题：弹出提示框：...
答案：这个x不是报错。...
chunk_id：a9a3e4c9..., d8d5ecb6..., 62653ea3..., e57c5894..., ...
```

#### 调用架构

**eval-tools 自闭环, 主应用零侵入**。

```
EvalRunner
    │
    ▼
RetrievalEvalTask
    │
    ├─ DashScopeClient.embed(query) ───────► DashScope text-embedding-v3
    │                                                │
    │                                                ▼
    │                                          [向量]
    │
    ├─ MilvusBulkReader.vectorSearch(vec, K=10) ────► Milvus (feature_document_vectors)
    │                                                │
    │                                                ▼
    │                                       [top-10 ChunkCandidate]
    │
    └─ RetrievalMetrics.{mrr|ndcg|recall}AtK ──────► metrics
```

每条 case 做一次 embedding + 一次 Milvus top-10 检索，K=5 和 K=10 的指标同时算出。

#### 指标语义（宽松集合标注）

> ⚠ Ground truth 是**一个相关 chunk 集合**（规模 7~36），不是单个 chunk。所有指标按"top-K 命中集合任一元素即算召回"语义计算。

| 指标 | 数学定义 | 业务语义 |
|---|---|---|
| **MRR@K** | top-K 内首个命中相关 chunk 的 rank 倒数；全 miss = 0 | "系统能否在前 K 给出至少一个相关结果, 且多靠前" |
| **NDCG@K** | 二值相关性 DCG / IDCG，IDCG 取 min(K, \|expected\|) 个理想位置 | "top-K 的相关性排序与理想排序的接近度" |
| **Recall@K** | \|top-K ∩ expected\| / \|expected\| | "前 K 召回平均覆盖标注集合的比例" |
| **hit_rate@K** | top-K 至少命中 1 个 expected 的 case 占比 | "系统会不会答非所问" |

**严格集合 vs 宽松集合的选择**：考虑过严格语义（对每个 expected 单独算 rank 再平均），但 expected 集合中位数 13、K=5 时 MRR 上限只有 `5/13 ≈ 0.38`，数字毫无解释力。宽松语义贴合标注本意（"这些都算相关的池子"），也符合业界 BEIR 等基准在 multi-relevant 场景下的默认做法。

**Recall 上限说明**：由于 expected 集合大（中位数 13、最大 36），Recall@5 物理上限典型在 0.15~0.7 之间，绝对值偏低是诚实的基线刻画，不代表系统差。NDCG 与 MRR 才更接近"用户感受到的排序质量"。

#### 当前数字（2026-05-20）

```
mrr@5    = 0.9028   ← 大部分命中在 top-1 / top-2
ndcg@5   = 0.8356
recall@5 = 0.3224   ← 物理上限附近 (上限 ≈ 5/13 = 0.38)
hit_rate@5 = 100%   ← 24/24 全部 top5 至少命中
```

**关键洞察**：`mrr@5 == mrr@10`、`ndcg@10 < ndcg@5`、`recall@10 / recall@5 = 1.64x` 这三条共同指向同一个系统特征——**召回长尾短**。能找对最相关的几个，但深度不足。后续 rerank / 多路融合优化有明显空间。

---

### 4.4 端到端延迟（`latency`）

#### 评估什么

从用户按回车到完整答案到位的端到端延迟。两个核心指标：

| 指标 | 含义 |
|---|---|
| **first_token** | 请求发出 → 收到第一个 token event（首字时延，流式 UX 的核心指标） |
| **total** | 请求发出 → 收到 done event（端到端总时延） |

#### 调用架构

**真打真实生产端点**——评估器扮演真实用户，先登录拿 token，再调 SSE 端点测延迟。

```
EvalRunner
    │
    ▼
LatencyEvalTask
    │
    ├─ EvalHttpClient.login(user, password) ────► /api/auth/login
    │                                                  │
    │                                                  ▼
    │                                         [JWT token]
    │
    └─ for each (case × 3 runs):
           │
           ▼ Authorization: Bearer <token>
       EvalHttpClient.streamSse(...) ────► /api/graph/chat-stream (SSE)
           │
           ▼
       逐 event 回调 (meta / token / done / error)
           │
           ▼
       记录 t0 → t_first_token → t_done
```

走**完整生产路径**：AuthInterceptor 鉴权 + UserContext set + chat_session DB 落库 + history 加载 + Graph stream + SSE 序列化全链路。**数字即用户真实感受**，不需要追加"不含 XX"注释。

#### 评估方法学

- **流量样本**：复用 `eval-set.txt` 24 条业务 query
- **每条跑 3 轮**：第 1 轮 warmup 丢弃（JVM JIT / Spring 冷启动 / 连接池建立），后 2 轮算入指标
- **串行执行**：并发会让数字虚低（线上不会一个用户连发 24 个请求）
- **超时**：单次 60 秒，超时算 fail，不毁全局
- **百分位算法**：线性插值法（numpy / scipy 默认实现），样本量小时数字更平滑

#### 当前数字（2026-05-20）

```
first_token P50 = 1485ms       total P50 = 6688ms
first_token P95 = 3194ms       total P95 = 8477ms
first_token min = 1149ms       total min = 4396ms
first_token max = 3918ms       total max = 8903ms

P95 / P50 倍率 = 2.15x  (健康范围, > 5x 才算长尾失控)
```

#### 已知盲点（评估流量代表性）

`eval-set.txt` 24 条是基于真实业务文档改编的 RAG 主链路 query，**不包含闲聊 query**。因此本基线**未体现 chitchat 短路**（理论上能省 4~6 秒）的优化效果。

这是个**有意为之的范围划分**，不是 bug：
- 业务真实流量本来就以 RAG 主链路为主，本基线代表主链路负载
- 闲聊短路优化效益应在专属的 chitchat 流量子集上单独评估，避免混合稀释主链路 P50/P95 的可解释性

后续若做闲聊优化对照，会建立单独的 `eval-set-chitchat.txt` 流量子集。

#### 评估期 DB 痕迹

每次 SSE 请求会在 `chat_session` / `chat_message` 表落数据，全部挂在评估账号（`sys_user.username='user'`, id=3）名下。不构成数据污染——评估账号天然隔离，需要时执行：

```sql
DELETE FROM chat_session WHERE user_id = 3;
-- chat_message 通过外键级联或单独删除
```

---

## 5. Ground Truth 重建工具（EvalSetRebuilder）

### 5.1 解决什么问题

人工标注 RAG ground truth 有个固有痛点：**标注者很难穷举所有"相关 chunk"**——文档库里有几千个 chunk，标注者凭记忆只能列出明显相关的几个，大量"也算相关"的 chunk 漏标。这种漏标会让评估指标系统性偏低，掩盖真实质量。

`GroundTruthAuditor`（项目早期工具）做了第一步：用 LLM-as-Judge 扫描每条 case，对全库 chunk 做"是否相关"判断，输出"疑似漏标"清单。但它只输出报告，不能直接写回评估集。

`EvalSetRebuilder` 是 Batch 5-A 引入的**半自动重建工具**，把审计报告转化为对 `eval-set.txt` 的实际修改。

### 5.2 方案：双模型 LLM-as-Judge + 人工裁决

```
GroundTruthAuditor 输出 audit-report-{ts}.json
                          │
                          │ (含: case×chunk 的双模型判断
                          │   turbo 模型 verdict + plus 模型 verdict + 各自 reasoning)
                          ▼
            EvalSetRebuilder (Stage 1: 生成裁决文件)
                          │
                          ▼
    ┌─────────────────────┴──────────────────────┐
    │                                            │
    ▼ suspectedMissing                           ▼ disagreement
(双模型都 YES/PARTIAL,                      (两个模型分歧)
 自动接受)                                  → 人工 review 裁决
    │                                            │
    │                                            ▼
    │                              生成 disagreement-review-{ts}.txt
    │                              (人工编辑 verdict=ACCEPT/REJECT)
    │                                            │
    └─────────────────────┬──────────────────────┘
                          ▼
            EvalSetRebuilder (Stage 2: 写回)
                          │
                          ▼
        修改 eval-set.txt 的 chunk_id 行
        (surgical 字节级写回, 其他字节保持原样)
                          │
                          ▼
            自动备份 .bak 文件
```

### 5.3 两阶段使用流程

**前置**：先跑一次 `GroundTruthAuditor` 拿到 `audit-report-{ts}.json`。

**Stage 1 — 生成裁决文件**

```bash
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.rebuild.EvalSetRebuilder
```

无参数运行 → 自动找最新的 audit-report → 生成 `disagreement-review-{ts}.txt`。

打开这个文件，逐条 chunk 在 `verdict:` 行填 `ACCEPT` 或 `REJECT`：
- `ACCEPT` = 加入 expectedChunks
- `REJECT` = 不加入（默认；留空 / 拼写错都算 REJECT）

每条 chunk 已附带 turbo 和 plus 两个模型的 verdict 与 reasoning，便于快速判断。

**Stage 2 — 写回**

```bash
mvn exec:java -Dexec.mainClass=com.wzh.agentdemo.evaltools.rebuild.EvalSetRebuilder \
              -Dexec.args="--decisions=disagreement-review-{ts}.txt"
```

读取你的裁决文件，把 ACCEPT 的 chunk 合并进 `eval-set.txt` 对应 case 的 chunk_id 行。其他字节完全不动。

### 5.4 设计要点

| 设计 | 原因 |
|---|---|
| **自动接受 suspectedMissing**（双模型都 YES） | 双模型独立同意的高置信度结论, 人工再 review 一遍不增加质量 |
| **人工裁决 disagreements**（双模型分歧） | 分歧本身是高价值信号, 人工裁决能修正 LLM 的系统性偏见 |
| **字节级 surgical 写回** | 只改 chunk_id 行, 不破坏文件其他字节（注释 / 空行 / 编码 / 换行符） |
| **自动 .bak 备份** | 任何写回前都备份, 操作失误可秒回滚 |
| **容错 verdict** | 拼写错 / 留空 / 大小写 = REJECT, 不抛异常打断流程 |
| **dry-run 模式** | 加 `--dry-run` 只打印差异, 不实际写回 |

### 5.5 实际效果

Batch 5-A 工具首次使用：

- audit-report 共 330 条疑似漏标 chunk
- 双模型完全同意（自动接受）约 70%
- 双模型分歧（人工 review）约 15 条
- 实际 review 耗时约 3 分钟
- 重建后 eval-set.txt 的 expectedChunks 平均规模从 ~2 提升到 ~13

**对评估数字的影响**：基于新 ground truth 跑出的 baseline 比旧 ground truth 更接近系统真实质量（MRR@5 从近似 0 提升到 0.90），证明工具本身有效。

---

## 6. 架构设计要点

### 6.1 评估器与主应用的隔离 / 解耦

eval-tools **编译期完全独立**——不引用主应用任何代码、不打入主应用 jar、独立 Maven 模块、独立 main 入口。运行时通过 HTTP 和直连基础设施访问被评估对象。

这种设计的代价：每次评估前要确保主应用启动；价值：评估代码可以独立演化、可以从外部跑（CI 流水线友好）、主应用代码改动不会触发评估代码的连锁修改。

### 6.2 四个任务的调用路径混合策略

| Task | 路径 | 理由 |
|---|---|---|
| `intent` | HTTP → `/internal/eval/intent` | 分类器在 Spring 容器中, 必须经过 Spring DI 才能拿到正确实例 |
| `route` | 纯函数本地复制 | 路由是纯条件判断, 复制 50 行换"零依赖 + 毫秒级速度" |
| `retrieval` | DashScope HTTP + Milvus 直连 | 评估底层向量质量, 不能经过主应用的 rerank / RRF 加成, 直连最干净 |
| `latency` | HTTP → `/api/graph/chat-stream` (SSE) | 测真实用户体验, 必须走完整生产路径 (含 AuthInterceptor / DB / SSE) |

这不是"挑了最方便的方法"——每条路径都有明确的语义考虑。

### 6.3 端到端真实性 vs 评估速度的权衡

注意 `latency` 任务是唯一走"真实生产路径含 DB"的任务。这是有意的：

- `intent` / `retrieval`：测的是**子系统能力**，不需要真实 DB 写入
- `latency`：测的是**用户体验**，必须含全链路开销

如果 `latency` 也走"裁剪过的内部端点"，简历讲到时永远需要解释"不含 X 不含 Y"，破功。

### 6.4 评估期 DB 污染的处理

`latency` 任务每次 SSE 请求会落库一条 user message + assistant message。**没有用事务回滚**——理由：

1. 评估账号天然独立（user_id=3），不与真实用户混在一起
2. 需要时一条 SQL 即可清理
3. 这些数据反而是真实流量样本，未来可以反过来作为评估 corpus 来源
4. 加事务回滚的复杂度高于收益

---

## 7. 评估发现的已知问题

### 7.1 当前 5 个真实 bug 清单

| # | 任务 | query | 期望 | 实际 | 根因 |
|---|---|---|---|---|---|
| 1 | intent | 今天天气怎么样 | chitchat | how_to | 关键词 `怎么` 撞车 |
| 2 | intent | 我的工单 TK-... 现在什么状态 | default | admin_command | LLM prompt 边界缺失 |
| 3 | intent | 苹果好吃还是橘子好吃 | default | chitchat | LLM 偏向闲聊 |
| 4 | intent | 帮我写一首关于春天的诗 | default | chitchat | 同上 |
| 5 | route | 联系一下客服 | ticket_agent | knowledge_answer | TICKET_PATTERN 正则未覆盖 |

### 7.2 为什么暂不修

**保留作"评估驱动调优"一刀的演示素材**。这五个 bug 形成一个完整的故事线：

> "建立评估 CI 之后，第一次跑就暴露了 5 个原本看不见的真实问题。每个问题都对应一个具体优化点：关键词字典完善 / prompt 边界 / 标注口径讨论 / 正则增强。逐一修复后再跑评估，验证修复有效且没有引入回归。"

这个故事比"我直接修了 bug"更有说服力——它证明评估 CI 的价值，而不是说"我有评估 CI"。

### 7.3 流量样本盲点

`latency` 任务的 24 条 query 均为 RAG 主链路问句，**不覆盖 chitchat 短路路径**。这是有意为之的范围划分（详见 4.4 节末尾），不是缺陷。

---

## 8. 扩展指南

### 8.1 加新评估任务

1. 实现 `EvalTask` 接口（`name` / `displayName` / `run`）
2. 在 `EvalRunner.main` 里调 `register(registry, new YourTask())`
3. 如果需要新 ground truth 数据源，在 `src/main/resources/` 加文件，在 `AuditConfig` 加常量
4. 如果需要主应用配合，在主应用加 `/internal/eval/{your-endpoint}`（X-Internal-Api-Key 鉴权）

### 8.2 加新指标

在已有 task 内修改：

1. 算出新指标的值
2. `metrics.put("your_metric_name", value)`
3. 报告侧 `UniversalEvalReporter` 自动按 metrics map 顺序展示，无需改动

### 8.3 自定义报告格式

`UniversalEvalReporter` 当前输出 markdown。要换格式（HTML / JSON / 控制台彩色）只需实现一个新 reporter 类，在 `EvalRunner` 末尾追加调用即可，既有 markdown 输出保留。

---

## 9. 文件结构与责任划分

```
eval-tools/
├── pom.xml                                    # 独立 Maven 模块, shade 默认入口 GroundTruthAuditor
├── README.md                                  # 本文档
└── src/main/
    ├── java/com/wzh/agentdemo/evaltools/
    │   ├── EvalRunner.java                    # 【评估 CI 主入口】多任务编排
    │   ├── GroundTruthAuditor.java            # 【既有, 零改动】早期审计脚本
    │   │
    │   ├── config/
    │   │   └── AuditConfig.java               # 全局常量 (Milvus / DashScope / 评估账号 / 路径)
    │   │
    │   ├── http/
    │   │   └── EvalHttpClient.java            # 统一 HTTP 客户端 (postJson / login / streamSse)
    │   │
    │   ├── llm/
    │   │   ├── DashScopeClient.java           # 【既有】embed / chat
    │   │   └── ChunkRelevanceJudge.java       # 【既有】LLM-as-Judge for ground truth audit
    │   │
    │   ├── milvus/
    │   │   └── MilvusBulkReader.java          # 【既有】vectorSearch + 全量拉取
    │   │
    │   ├── model/
    │   │   ├── AuditResult.java               # 【既有】GroundTruthAuditor 输出
    │   │   ├── AuditVerdict.java              # 【既有】
    │   │   ├── ChunkCandidate.java            # 【既有】top-K 召回结果
    │   │   ├── EvalCase.java                  # 【新】检索/延迟评估 case 模型
    │   │   ├── EvalTaskResult.java            # 【新】统一任务结果模型
    │   │   └── IntentEvalCase.java            # 【新】意图/路由评估 case 模型
    │   │
    │   ├── parser/
    │   │   ├── EvalSetParser.java             # 【新】eval-set.txt 解析
    │   │   ├── IntentEvalSetParser.java       # 【新】eval-set-intent.txt 解析
    │   │   └── KeywordExtractor.java          # 【既有】关键词抽取 (GroundTruthAuditor 用)
    │   │
    │   ├── metrics/
    │   │   └── RetrievalMetrics.java          # 【新】MRR / NDCG / Recall 纯函数工具
    │   │
    │   ├── route/
    │   │   └── RouteSimulator.java            # 【新】RouteUtil + MainGraphConfig 纯函数副本
    │   │
    │   ├── rebuild/                           # 【新, Batch 5-A】ground truth 重建工具
    │   │   ├── EvalSetRebuilder.java          # 主入口, CLI 调度两阶段
    │   │   ├── DisagreementReviewWriter.java  # Stage 1: 渲染裁决文件
    │   │   ├── DisagreementReviewParser.java  # Stage 2 输入端: 解析人工裁决
    │   │   ├── EvalSetMerger.java             # surgical 字节级写回
    │   │   └── DisagreementDecision.java      # 裁决模型
    │   │
    │   ├── report/
    │   │   ├── AuditReporter.java             # 【既有, 零改动】GroundTruthAuditor 报告
    │   │   └── UniversalEvalReporter.java     # 【新】EvalRunner markdown 报告
    │   │
    │   └── task/                              # 【新, Batch 1 + 3/4/5-B/6】
    │       ├── EvalTask.java                  # 任务接口
    │       ├── IntentEvalTask.java            # 意图分类 (Batch 3)
    │       ├── RouteEvalTask.java             # 路由 (Batch 4)
    │       ├── RetrievalEvalTask.java         # 检索质量 (Batch 5-B)
    │       └── LatencyEvalTask.java           # 端到端延迟 (Batch 6)
    │
    └── resources/
        ├── eval-set.txt                       # 检索 / 延迟 ground truth (24 case)
        ├── eval-set.txt.bak                   # 自动备份
        ├── eval-set-bak.txt                   # 重建前的旧版本（保留对照）
        ├── eval-set-intent.txt                # 意图 / 路由 ground truth (21 case)
        └── logback.xml                        # 日志配置
```

---

## 10. 关键技术决策记录（ADR-lite）

记录评估 CI 主线建设过程中几个非显然的技术选择。每条采用 "上下文 → 决策 → 代价 → 状态" 的结构。

### 10.1 chunk_id 严格匹配 vs content 软匹配

**上下文**：Milvus 中文档分块时存在"内容几乎相同但 chunk_id 不同"的情况（例如同一段话被两个 feature 各自分块）。匹配时是按 chunk_id 严格比对，还是按 content 计算文本相似度？

**决策**：**chunk_id 严格匹配**。

**代价**：
- 必须建立"哪些 chunk_id 算相关"的完整集合标注，遗漏会让指标偏低
- 引入 EvalSetRebuilder 半自动重建工具来补足标注

**状态**：✅ 采纳。代价值得——chunk_id 匹配是确定性的，content 软匹配会引入相似度阈值的二次调参问题，评估自身的稳定性会受影响。

### 10.2 MRR/NDCG 用宽松集合 vs 严格集合语义

**上下文**：expected 是"一个相关 chunk 集合"（规模 7~36），不是单个 chunk。MRR 该按"首次命中集合"算一次，还是按"对每个 expected 都算一次再平均"？

**决策**：**宽松集合语义**——top-K 命中集合任一元素即算召回一次。

**代价**：
- 单 case 内多个相关 chunk 即使全部命中也只贡献一个 MRR 分量
- 数字会比"严格集合"略高，可能给人"过于乐观"的错觉

**状态**：✅ 采纳。理由：
- 严格语义在 expected=30 + K=5 时 MRR 上限只有 5/30=0.17，数字毫无解释力
- 宽松语义贴合标注本意（"这些都算相关池"）
- 业界 BEIR 等基准在 multi-relevant 场景默认就是宽松语义

### 10.3 latency 走真实 SSE 端点 vs 内部裁剪端点

**上下文**：测延迟时让评估器调真实 `/api/graph/chat-stream`（带 token 鉴权 + DB 落库），还是新增一个 `/internal/eval/chat-stream`（绕鉴权 + 跳过 DB）？

**决策**：**真实 SSE 端点**。

**代价**：
- eval-tools 需要 login 拿 token、自实现 SSE 解析
- DB 会留下评估期 chat_session 数据

**状态**：✅ 采纳。理由：
- 延迟评估的核心价值就是"测真实用户体验"，绕过任何一层都让数字失真
- 简历讲到时不需要追加"不含 XX"注释
- DB 痕迹通过评估账号天然隔离，可控

### 10.4 ground truth 重建用 LLM-as-Judge 而非全人工

**上下文**：人工标注的 ground truth 漏标率高（标注者凭记忆只能列出明显相关的）。补全方案：全人工二次审核（高质量但慢，约 8 小时）vs LLM-as-Judge 双模型 + 人工裁决分歧（约 3 分钟人工时间）。

**决策**：**LLM-as-Judge 双模型方案**——qwen-turbo + qwen-plus 各自独立判断，完全同意自动接受，分歧人工裁决。

**代价**：
- 完全同意但实际错的情况（双模型同向偏见）会漏过
- 工具复杂度增加（约 600 行代码）

**状态**：✅ 采纳。理由：
- 单模型容易过度乐观，双模型独立判断显著降低偏见
- 重建后基线 MRR@5 从近 0 提升到 0.90，证明效果显著
- 工具是一次性投入，永久受益

### 10.5 route 任务纯函数复制而非走 HTTP

**上下文**：路由逻辑（RouteUtil）能否复制成评估器侧的纯函数副本？

**决策**：**纯函数复制**，在 `RouteSimulator` 注释顶部留同步纪律 checklist。

**代价**：
- RouteUtil 重构时必须同步更新副本，否则评估失真
- 一旦忘记同步，会有静默的"假绿灯"风险

**状态**：✅ 采纳。理由：
- 路由是纯条件判断，复制成本仅约 50 行
- 评估速度从秒级降到毫秒级
- 同步纪律通过注释 checklist 强制执行，被忘记的概率可控

### 10.6 评估器编译期独立 vs 作为主应用子模块

**上下文**：eval-tools 是独立 Maven 模块，还是放在主应用的 src/test 下？

**决策**：**独立模块**，不依赖主应用任何代码。

**代价**：
- 失去 Spring DI 的便利，需要手工管理客户端实例
- 主应用接口签名变化时评估代码可能编译报错（但运行时只看 JSON 字段名）

**状态**：✅ 采纳。理由：
- 评估器与主应用应该有清晰的角色边界（一个测量, 一个被测）
- 独立模块可以独立打 jar、独立部署到 CI 流水线
- 主应用代码改动不会触发评估代码的连锁修改

---

## 11. CHANGELOG

评估 CI 主线的迭代时间线。

### Batch 1 — 评估框架骨架（2026-05-19）

- 新增 `EvalRunner` 主入口、`EvalTask` 接口、4 个空骨架 task（intent / route / retrieval / latency）
- 新增 `IntentEvalCase` / `EvalTaskResult` 模型
- 新增 `IntentEvalSetParser` + `UniversalEvalReporter`
- `AuditConfig` 末尾纯追加 3 个常量
- 既有 `GroundTruthAuditor` 全部零改动，pom shade 默认入口保持不变

### Batch 2 — 意图评估集标注（2026-05-19）

- 落地 `eval-set-intent.txt`：21 条样例（6 闲聊 + 8 管理员 + 4 工单 + 3 兜底）

### Batch 3 — 意图分类准确率任务（2026-05-19）

- 新增 `EvalHttpClient` + `IntentEvalTask` 真实现
- 主应用新增 `InternalEvalController`（端点 `POST /internal/eval/intent`, X-Internal-Api-Key 鉴权）
- **首次跑分**：accuracy 80.95%（17/21），暴露 4 个真实 bug

### Batch 4 — 路由正确率任务（2026-05-19）

- 新增 `RouteSimulator`（RouteUtil + MainGraphConfig 纯函数副本，含同步纪律 checklist）
- `RouteEvalTask` 真实现：0 HTTP 调用
- **首次跑分**：accuracy 95.24%（20/21），暴露 1 个真实 bug

### Batch 5-A — Ground Truth 重建工具（2026-05-19）

- 新增 `rebuild` 包：`EvalSetRebuilder` + `DisagreementReviewWriter` + `DisagreementReviewParser` + `EvalSetMerger` + `DisagreementDecision`
- 两阶段流程（生成裁决文件 → 人工 review → 写回），surgical 字节级写回，自动 .bak 备份，dry-run 模式
- 实际使用：约 15 条分歧人工 review，3 分钟完成
- `eval-set.txt` expectedChunks 平均规模从 ~2 提升到 ~13

### Batch 5-B — 检索质量任务（2026-05-20）

- 新增 `RetrievalMetrics`（MRR / NDCG / Recall 纯函数工具）
- `RetrievalEvalTask` 真实现：DashScope embed + 直连 Milvus
- **首次跑分**：MRR@5=0.9028, NDCG@5=0.8356, hit_rate@5=100%, Recall@5=0.3224
- 关键洞察：通过 K=5 vs K=10 指标对比揭示"召回长尾短"的系统特征

### Batch 6 — 端到端延迟任务（2026-05-20）

- 扩展 `EvalHttpClient` 增加 `login()` + `streamSse()`（既有 `postJson` 零改动）
- `LatencyEvalTask` 真实现：login 拿 token → 真打 SSE 端点 → 自实现 SSE 事件流解析
- 24 case × 3 轮（1 warmup + 2 measured）= 48 个 measured 样本
- **首次跑分**：first_token P50=1485ms / P95=3194ms, total P50=6688ms / P95=8477ms
- P95/P50 倍率 2.15x，延迟分布健康

### Batch 7 — 综合收尾（2026-05-20）

- 修复 LatencyEvalTask 的 `totalCount` 把 warmup 计入分母导致通过率显示为 66.67% 的 bug
- 落地本 README

---

## 12. 后续计划

评估 CI 主线已完结。后续将基于本评估基线推进：

1. **可观测性主线**（Prometheus + Grafana）—— 把评估指标接入实时监控，从离线评估转向在线评估
2. **评估驱动调优**—— 逐一修复评估发现的 5 个真实 bug，每次修复后跑评估验证有效且无回归
3. **在线 RAG 评估**—— LLM-as-Judge 监控线上召回质量退化（Self-RAG 的前置条件）
4. **闲聊流量子集**—— 建立 `eval-set-chitchat.txt`，专门评估 chitchat 短路优化效益

---

*本文档由 AgentDemo 评估 CI 主线 Batch 7 落地（2026-05-20）。维护者：Mr. Wang。*
