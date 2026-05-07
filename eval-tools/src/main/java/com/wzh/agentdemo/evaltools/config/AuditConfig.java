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
    public static final String MILVUS_HOST = "10.82.12.51";
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
}
