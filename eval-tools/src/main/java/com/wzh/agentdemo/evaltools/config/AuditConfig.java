package com.wzh.agentdemo.evaltools.config;

/**
 * 审计工具配置常量。
 * <p>优化阶段一次性脚本，配置直接写死，不读 application.yml。</p>
 */
public final class AuditConfig {

    private AuditConfig() {}

    // ============ DashScope ============
    public static final String DASHSCOPE_API_KEY = "sk-18ef5769878142bfbb75b3f7a0f3a823";
    public static final String EMBEDDING_MODEL = "text-embedding-v3";
    public static final String JUDGE_MODEL_TURBO = "qwen-turbo";
    public static final String JUDGE_MODEL_PLUS = "qwen-plus";

    public static final String EMBEDDING_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    public static final String CHAT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    // ============ Milvus ============
    public static final String MILVUS_HOST = "10.82.13.61";
    public static final int MILVUS_PORT = 19530;
    public static final String MILVUS_COLLECTION = "feature_document_vectors";

    // ============ Pipeline 阈值 ============
    /** Top-K 向量召回 */
    public static final int VECTOR_TOP_K = 10;
    /** 关键词预筛后保留候选上限 (避免 LLM 调用爆炸) */
    public static final int KEYWORD_CANDIDATE_LIMIT = 50;
    /** 关键词最短长度 (单字符意义太弱) */
    public static final int MIN_KEYWORD_LEN = 2;

    // ============ 输出 ============
    public static final String OUTPUT_DIR = "rag-eval-output";
    public static final String EVAL_SET_RESOURCE = "eval-set.txt";

    // ============ HTTP ============
    public static final long HTTP_TIMEOUT_SECONDS = 60L;

    // ============ LLM 重试 ============
    public static final int LLM_MAX_RETRY = 2;
    public static final long LLM_RETRY_BACKOFF_MS = 1000L;

    // ============ 评估 CI (Batch 1 追加, 不影响既有 GroundTruthAuditor) ============

    /** 意图 / 路由评估集 (Batch 2 由 Mr. Wang 标注后落地到 src/main/resources/) */
    public static final String INTENT_EVAL_SET_RESOURCE = "eval-set-intent.txt";

    /** 主应用 base URL, Batch 3 (意图评估 HTTP 调用) / Batch 6 (端到端延迟) 使用 */
    public static final String MAIN_APP_BASE_URL = "http://localhost:9999";

    /**
     * 主应用 internal 接口鉴权 key, 与主应用 application.yml 的 internal.api-key 对齐.
     * <p>用于调用 /internal/eval/intent (Batch 3 主应用侧新增) 等评估端点.</p>
     */
    public static final String INTERNAL_API_KEY = "internal-secret-key-change-me";

    // ============ 评估 CI Batch 6 (端到端延迟) 追加 ============

    /**
     * 端到端延迟评估用的测试账号 (主应用 sys_user 表, role=user).
     * <p>评估器启动时调 /api/auth/login 拿 token, 之后所有 SSE 请求带此 token.
     * 优先从环境变量 EVAL_USER 读, 缺失则用此默认值. 主应用密码当前为明文比对.</p>
     * <p><b>DB 准备</b>: 评估前需在主应用 sys_user 表插入此账号. 已知存在记录:
     * id=3, username=user, password=user123, role=user.</p>
     */
    public static final String EVAL_USERNAME_DEFAULT = "user";
    public static final String EVAL_PASSWORD_DEFAULT = "user123";

    /**
     * 解析评估账号 (env 优先, 默认值兜底). 单独抽方法是为了测试时易于 mock.
     */
    public static String resolveEvalUsername() {
        String env = System.getenv("EVAL_USER");
        return env != null && !env.isBlank() ? env : EVAL_USERNAME_DEFAULT;
    }

    public static String resolveEvalPassword() {
        String env = System.getenv("EVAL_PASSWORD");
        return env != null && !env.isBlank() ? env : EVAL_PASSWORD_DEFAULT;
    }

    // ============ 延迟评估参数 (Batch 6) ============

    /** 每个 query 跑总轮数 (含 warmup) */
    public static final int LATENCY_TOTAL_RUNS = 3;
    /** 前 N 轮作为 warmup 丢弃, 不计入指标 */
    public static final int LATENCY_WARMUP_RUNS = 1;
    /** 单次 SSE 请求超时 (秒). 60s 容忍最坏 LLM 兜底. */
    public static final int LATENCY_SSE_TIMEOUT_SEC = 60;
}