package com.wzh.service.intent;

import com.wzh.model.intent.IntentClassificationResult;

/**
 * 意图分类器统一接口.
 *
 * <p>采用策略模式, 三个实现:
 * <ul>
 *   <li>{@code KeywordIntentClassifier} - 关键词规则匹配 (Step 2)</li>
 *   <li>{@code LlmIntentClassifier}     - qwen-turbo 结构化输出兜底 (Step 3)</li>
 *   <li>{@code HybridIntentClassifier}  - 关键词优先 + LLM 兜底, 暴露给业务层 (Step 3)</li>
 * </ul>
 *
 * <p>所有实现必须保证: <b>不抛异常</b>. 失败时返回
 * {@link IntentClassificationResult#defaultResult(String)} 兜底, 由调用方决定后续处理.</p>
 *
 * @author wzh
 * @since 2026-05-08
 */
public interface IntentClassifier {

    /**
     * 对查询进行意图分类.
     *
     * @param query 用户原始查询文本 (不应为 null/空, 调用方负责前置校验)
     * @return 分类结果, 永不为 null; 失败时返回 {@code Intent.DEFAULT} 兜底结果
     */
    IntentClassificationResult classify(String query);
}