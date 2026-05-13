package com.wzh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FAQ 检索相关配置 (第四刀引入).
 *
 * <p><b>设计背景</b>: 第四刀将 FAQ 从主 collection 拆出后, FaqRetrieveNode
 * 走独立的单路向量检索流水线 (不接 reranker / rewriting), 配置项独立维护.</p>
 *
 * <p><b>generalMarker 说明</b>: 沿用 AgentService.learnFaq() 既定约定 —
 * 通用 FAQ (relatedFeatureName 为 null) 在 Milvus 里以 feature_name="通用FAQ"
 * 存储. 这里把字面值提到配置, 应用层不再写死. 将来要改不影响调用方.</p>
 *
 * @author wzh
 * @since 2026-05-13
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "faq.retrieve")
public class FaqRetrieveProperties {

    /** FAQ 检索开关; false = FaqRetrieveNode 直接返回空 List */
    private boolean enabled = true;

    /** 单路向量检索 top-K */
    private int topK = 3;

    /** 通用 FAQ 的 feature_name 标识 */
    private String generalMarker = "通用FAQ";
}