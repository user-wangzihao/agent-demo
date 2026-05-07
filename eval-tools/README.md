# eval-tools

RAG 评估辅助工具（与生产代码隔离）。

## 当前工具

### Ground Truth Auditor
对 eval-set 中的每个 case，自动找出"未在 expectedChunks 中、但实际能回答 query"的疑似漏标 chunk。

**Pipeline**:
1. 解析 `eval-set.txt` (24 个 case)
2. 对每个 case：
    - DashScope embedding(query) → 向量
    - Milvus 向量 Top-10 检索
    - Milvus 关键词预筛（content like %关键词%）
    - 合并去重 + 排除已标注 chunk
    - qwen-turbo 逐个判定 (YES/PARTIAL/NO)
    - 对 YES/PARTIAL 的 chunk → qwen-plus 复核
3. 输出 `rag-eval-output/audit-report-{时间戳}.{json,md}`

**判定准则**:
- 双模型都判 YES/PARTIAL → 进入"疑似漏标"清单
- 仅 turbo 判 YES/PARTIAL，plus 判 NO → 进入"模型分歧"清单（参考）

## 运行

```bash
cd eval-tools
mvn -DskipTests package
java -jar target/eval-tools-1.0.0-SNAPSHOT.jar
```

## 配置

所有配置硬编码在 `AuditConfig.java`。优化阶段一次性脚本，不读 application.yml。

如需切换 Milvus / DashScope 配置，直接改 `AuditConfig` 重新打包。

## 目录

```
eval-tools/
├── pom.xml
└── src/main/
    ├── java/com/wzh/agentdemo/evaltools/
    │   ├── GroundTruthAuditor.java       (主入口)
    │   ├── config/AuditConfig.java
    │   ├── model/                        (EvalCase / ChunkCandidate / AuditVerdict / AuditResult)
    │   ├── parser/                       (EvalSetParser / KeywordExtractor)
    │   ├── milvus/MilvusBulkReader.java
    │   ├── llm/                          (DashScopeClient / ChunkRelevanceJudge)
    │   └── report/AuditReporter.java
    └── resources/
        ├── eval-set.txt                  (你提供的评估集文本)
        └── logback.xml
```
