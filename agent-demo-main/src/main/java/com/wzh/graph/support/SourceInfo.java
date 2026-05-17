package com.wzh.graph.support;

import lombok.Data;

/**
 * 检索来源元信息 (前端"来源"列表的展示载体).
 *
 * <p><b>由来</b>: 第六刀 Batch 3 前, 这个类是 {@code AgentService} 的静态内部类
 * ({@code AgentService.SourceInfo}). Graph 链路 (MergerNode / RetrievalPostProcessor /
 * MainGraphSseController) 已经不依赖 AgentService 的业务逻辑, 却被这个内部类强行
 * 拖回了对 AgentService 的编译期依赖, 阻碍 Batch 4 的 AgentService 整体下线.
 * Batch 3 把它独立成顶层类, 放在 Graph 自己的 support 包下.</p>
 *
 * <p><b>字段语义</b>:
 * <ul>
 *   <li>{@code featureName}: 文档来源下是功能名; FAQ 来源下复用此字段承载"问题摘要"
 *       (≤30 字, 见 {@link RetrievalPostProcessor#toFaqSourceInfoList}).</li>
 *   <li>{@code chunkType}: 文档来源是 chunk_type 原值; FAQ 来源固定为 "FAQ",
 *       前端据此切换展示样式.</li>
 *   <li>{@code score}: 检索相关度. 注意 Doc / FAQ 走不同 Milvus collection,
 *       两套向量距离不可跨类型直接比较 (这也是 MergerNode 采用"拼 Context 融合"
 *       而非"分数融合"的根本原因).</li>
 * </ul></p>
 *
 * <p><b>前端协议兼容</b>: 字段名 / 类型 / public 修饰符与原内部类完全一致,
 * 序列化结果不变, 前端无需任何改动.</p>
 *
 * @author wzh
 */
@Data
public class SourceInfo {
    public String featureName;
    public String chunkType;
    public float score;
}