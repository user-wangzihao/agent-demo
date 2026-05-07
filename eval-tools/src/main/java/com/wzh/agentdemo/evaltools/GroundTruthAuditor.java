package com.wzh.agentdemo.evaltools;

import com.wzh.agentdemo.evaltools.config.AuditConfig;
import com.wzh.agentdemo.evaltools.llm.ChunkRelevanceJudge;
import com.wzh.agentdemo.evaltools.llm.DashScopeClient;
import com.wzh.agentdemo.evaltools.milvus.MilvusBulkReader;
import com.wzh.agentdemo.evaltools.model.AuditResult;
import com.wzh.agentdemo.evaltools.model.AuditVerdict;
import com.wzh.agentdemo.evaltools.model.ChunkCandidate;
import com.wzh.agentdemo.evaltools.model.EvalCase;
import com.wzh.agentdemo.evaltools.parser.EvalSetParser;
import com.wzh.agentdemo.evaltools.parser.KeywordExtractor;
import com.wzh.agentdemo.evaltools.report.AuditReporter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评估集 Ground Truth 自动审计入口。
 *
 * <p>用法:</p>
 * <pre>
 *   1. 把 eval-set 文本放在 src/main/resources/eval-set.txt
 *   2. mvn package
 *   3. java -jar target/eval-tools-1.0.0-SNAPSHOT.jar
 * </pre>
 */
@Slf4j
public class GroundTruthAuditor {

    public static void main(String[] args) throws Exception {
        new GroundTruthAuditor().run();
    }

    public void run() throws Exception {
        long t0 = System.currentTimeMillis();

        // ===== 1. 加载并解析 eval set =====
        String text = loadResource(AuditConfig.EVAL_SET_RESOURCE);
        List<EvalCase> cases = new EvalSetParser().parse(text);
        if (cases.isEmpty()) {
            log.error("未解析到任何 case，请检查 eval-set 文件格式");
            return;
        }

        // ===== 2. 准备客户端 =====
        DashScopeClient ds = new DashScopeClient();
        ChunkRelevanceJudge judge = new ChunkRelevanceJudge(ds);
        KeywordExtractor kwExtractor = new KeywordExtractor();
        AuditReporter reporter = new AuditReporter();

        List<AuditResult> results = new ArrayList<>();
        // 用于报告里展示 expectedChunks 的内容
        Map<String, ChunkCandidate> expectedDetailCache = new HashMap<>();

        try (MilvusBulkReader milvus = new MilvusBulkReader()) {

            // ===== 3. 逐 case 处理 =====
            for (EvalCase ec : cases) {
                log.info("─────────────── EvalId #{} [{} | {}] ───────────────",
                        ec.getEvalId(), ec.getCategory(), ec.getFeatureName());
                log.info("Query: {}", ec.getQuery());

                AuditResult r = auditOneCase(ec, milvus, ds, judge, kwExtractor);
                results.add(r);

                // 缓存 expectedChunks 详情
                Map<String, ChunkCandidate> expectedDetail = milvus.getByIds(ec.getExpectedChunks());
                expectedDetailCache.putAll(expectedDetail);
            }
        }

        // ===== 4. 输出报告 =====
        reporter.write(results, expectedDetailCache);

        long elapsed = System.currentTimeMillis() - t0;
        long suspectedCases = results.stream().filter(r -> !r.getSuspectedMissing().isEmpty()).count();
        int suspectedChunks = results.stream().mapToInt(r -> r.getSuspectedMissing().size()).sum();

        log.info("==========================================");
        log.info("审计完成 | 耗时 {} ms", elapsed);
        log.info("总 case 数: {}", results.size());
        log.info("发现疑似漏标的 case: {}", suspectedCases);
        log.info("疑似漏标 chunk 总数: {}", suspectedChunks);
        log.info("报告位于: {}", AuditConfig.OUTPUT_DIR);
        log.info("==========================================");
    }

    private AuditResult auditOneCase(EvalCase ec, MilvusBulkReader milvus, DashScopeClient ds,
                                     ChunkRelevanceJudge judge, KeywordExtractor kwExtractor) {

        // -- 3a. embedding --
        List<Float> queryVec;
        try {
            queryVec = ds.embed(ec.getQuery());
        } catch (Exception e) {
            log.error("embedding 失败 evalId={}, 跳过此 case: {}", ec.getEvalId(), e.getMessage());
            return AuditResult.builder().evalCase(ec).candidateCount(0).build();
        }

        // -- 3b. 向量 Top-K 检索 --
        List<ChunkCandidate> vectorHits = milvus.vectorSearch(queryVec, AuditConfig.VECTOR_TOP_K);
        log.info("向量召回 {} 条", vectorHits.size());

        // -- 3c. 关键词预筛 --
        List<String> keywords = kwExtractor.extract(ec.getQuery(), ec.getFeatureName());
        log.info("关键词: {}", keywords);
        List<ChunkCandidate> keywordHits = milvus.keywordPrefilter(keywords, AuditConfig.KEYWORD_CANDIDATE_LIMIT);
        log.info("关键词召回 {} 条", keywordHits.size());

        // -- 3d. 合并去重 + 排除 expectedChunks --
        Set<String> expected = new HashSet<>(ec.getExpectedChunks());
        Map<String, ChunkCandidate> mergedById = new LinkedHashMap<>();
        for (ChunkCandidate c : vectorHits) {
            if (expected.contains(c.getChunkId())) continue;
            mergedById.put(c.getChunkId(), c);
        }
        for (ChunkCandidate c : keywordHits) {
            if (expected.contains(c.getChunkId())) continue;
            ChunkCandidate exist = mergedById.get(c.getChunkId());
            if (exist == null) {
                mergedById.put(c.getChunkId(), c);
            } else {
                exist.setSource("BOTH");
            }
        }
        List<ChunkCandidate> candidates = new ArrayList<>(mergedById.values());
        log.info("候选 chunk (去重+排除标注后): {} 条", candidates.size());

        AuditResult result = AuditResult.builder()
                .evalCase(ec)
                .keywords(keywords)
                .candidateCount(candidates.size())
                .build();

        // -- 3e. qwen-turbo 初筛 --
        log.info("开始 qwen-turbo 初筛...");
        Map<String, AuditVerdict> turboVerdicts = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            ChunkCandidate c = candidates.get(i);
            AuditVerdict v = judge.judge(AuditConfig.JUDGE_MODEL_TURBO, ec.getQuery(), c.getContent());
            turboVerdicts.put(c.getChunkId(), v);
            log.debug("  [{}/{}] turbo {} -> {} conf={} reason={}",
                    i + 1, candidates.size(), c.getChunkId(), v.getVerdict(), v.getConfidence(), v.getReason());
        }
        long turboYesPartial = turboVerdicts.values().stream()
                .filter(v -> v.getVerdict() == AuditVerdict.Verdict.YES
                        || v.getVerdict() == AuditVerdict.Verdict.PARTIAL)
                .count();
        log.info("qwen-turbo 判定 YES/PARTIAL 共 {} 条，进入 plus 复核", turboYesPartial);

        // -- 3f. qwen-plus 复核 (仅对 YES/PARTIAL) --
        for (ChunkCandidate c : candidates) {
            AuditVerdict turboV = turboVerdicts.get(c.getChunkId());
            if (turboV.getVerdict() != AuditVerdict.Verdict.YES
                    && turboV.getVerdict() != AuditVerdict.Verdict.PARTIAL) {
                continue;
            }
            AuditVerdict plusV = judge.judge(AuditConfig.JUDGE_MODEL_PLUS, ec.getQuery(), c.getContent());
            log.debug("  plus {} -> {} conf={} reason={}",
                    c.getChunkId(), plusV.getVerdict(), plusV.getConfidence(), plusV.getReason());

            AuditResult.SuspectedMissing sm = AuditResult.SuspectedMissing.builder()
                    .candidate(c)
                    .turboVerdict(turboV)
                    .plusVerdict(plusV)
                    .build();

            boolean plusAgrees = plusV.getVerdict() == AuditVerdict.Verdict.YES
                    || plusV.getVerdict() == AuditVerdict.Verdict.PARTIAL;
            if (plusAgrees) {
                result.getSuspectedMissing().add(sm);
            } else {
                result.getDisagreements().add(sm);
            }
        }

        log.info("evalId #{} -> 疑似漏标 {} 条, 模型分歧 {} 条",
                ec.getEvalId(), result.getSuspectedMissing().size(), result.getDisagreements().size());

        return result;
    }

    private String loadResource(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Resource not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
