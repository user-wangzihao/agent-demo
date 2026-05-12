package com.wzh.graph;

/**
 * HelloGraph 测试用的 State Key 常量.
 *
 * <p>OverAllState 是 key-value 形态, 所有读写都基于 key. 把 key 提到常量类里:
 * <ul>
 *   <li>避免节点之间硬编码字符串拼写错误</li>
 *   <li>未来 IDE 重构 / 全局搜索都方便</li>
 *   <li>后续真实业务 Graph (GraphStateKeys) 会沿用这个模式</li>
 * </ul></p>
 *
 * @author wzh
 * @since 2026-05-11
 */
public final class HelloGraphKeys {

    /** 用户输入 (复用 OverAllState.DEFAULT_INPUT_KEY) */
    public static final String INPUT = "input";

    /** 节点输出 */
    public static final String OUTPUT = "output";

    private HelloGraphKeys() {
        // 工具类禁止实例化
    }
}