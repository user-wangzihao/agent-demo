package com.wzh.agentdemo.evaltools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一个候选 chunk 及其元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkCandidate {
    private String chunkId;
    private String content;
    private String featureName;
    private String chunkType;

    /** 来源标记: VECTOR / KEYWORD / BOTH */
    private String source;

    /** 向量召回时的排名 (1-based)，关键词召回为 null */
    private Integer vectorRank;
    /** 向量召回时的相似度分数 */
    private Double vectorScore;
}
