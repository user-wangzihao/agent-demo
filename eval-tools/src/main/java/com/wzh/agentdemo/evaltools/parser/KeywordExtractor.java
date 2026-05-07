package com.wzh.agentdemo.evaltools.parser;

import com.wzh.agentdemo.evaltools.config.AuditConfig;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 关键词提取器（改进版）。
 *
 * <p>策略：</p>
 * <ol>
 *   <li>注入 featureName 作为强信号关键词</li>
 *   <li>按标点和停用词切分，保留长度 ≥ 2 的中文片段（"整段优先"）</li>
 *   <li>剥离片段首尾的停用词，例如 "我在使用快速涂色功能的时候" → "快速涂色功能的时候"</li>
 *   <li>对长片段（≥6 字）额外剥离 3-4 字领域词</li>
 *   <li>过滤含高频虚词的切片，避免 Milvus OR like 召回爆炸</li>
 * </ol>
 *
 * <p>注：早期版本做 2/3/4 字全滑窗，导致单 query 产出 70+ 个关键词，
 * Milvus OR like 查询会召回几乎全库，毫无意义。本版改为更克制的策略。</p>
 */
public class KeywordExtractor {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "我", "为什么", "怎么", "如何", "什么", "这个", "那个",
            "使用", "功能", "时候", "可以", "需要", "要怎么", "为甚", "介绍", "一下",
            "用来", "做什么", "用户", "用于", "主要", "具体", "操作", "请问", "怎样",
            "这", "那", "有", "和", "与", "或", "及", "对", "中", "上", "下", "时",
            "出", "入", "里", "外", "前", "后", "上面", "下面", "里面", "外面",
            "一个", "几个", "几种", "哪些", "哪个", "我在", "弹出", "提示", "提示框", "对话框", "信息",
            "已执", "已执行", "为甚使用", "为什么使用", "如何使用", "怎么使用",
            "请", "并", "并将", "进行", "完成", "执行", "运行",
            "作用", "方式", "方法", "影响", "变化", "属性"
    );

    /** 切分用的标点和空白 */
    private static final String SPLIT_REGEX = "[\\s,，。、？！?!.;；:：\"'“”‘’()（）\\[\\]【】{}<>《》/\\\\\\-—_+=*&^%$#@~`|\\d]+";

    /** 长片段的拆分阈值 */
    private static final int LONG_SEG_THRESHOLD = 6;
    /** 领域词候选长度 */
    private static final int[] DOMAIN_TERM_LENGTHS = {3, 4};

    /**
     * @param query       用户问题
     * @param featureName case 所属功能模块名（如"快速涂色赋注解"），可为 null
     */
    public List<String> extract(String query, String featureName) {
        if (query == null || query.isBlank()) return List.of();

        Set<String> tokens = new LinkedHashSet<>();

        // (1) 注入 featureName（最强信号）
        if (featureName != null && !featureName.isBlank() && featureName.length() >= 2) {
            tokens.add(featureName);
        }

        // (2) 按标点/停用词切分
        String[] segments = query.split(SPLIT_REGEX);
        for (String seg : segments) {
            seg = seg.trim();
            if (seg.isEmpty() || STOP_WORDS.contains(seg)) continue;

            // (2a) 剥离首尾停用词后整段保留
            String trimmed = stripLeadingTrailingStopwords(seg);
            if (trimmed.length() >= AuditConfig.MIN_KEYWORD_LEN
                    && !STOP_WORDS.contains(trimmed)
                    && isMostlyChinese(trimmed)) {
                tokens.add(trimmed);
            }

            // (2b) 长片段额外剥离 3-4 字领域词
            if (trimmed.length() >= LONG_SEG_THRESHOLD) {
                addDomainTerms(tokens, trimmed);
            }
        }

        return tokens.stream()
                .filter(t -> t.length() >= AuditConfig.MIN_KEYWORD_LEN)
                .filter(t -> !STOP_WORDS.contains(t))
                .filter(this::isMostlyChinese)
                .collect(Collectors.toList());
    }

    /** 兼容签名（不带 featureName） */
    public List<String> extract(String query) {
        return extract(query, null);
    }

    /**
     * 剥离片段首尾的停用词。例如 "我在使用快速涂色功能的时候" → "快速涂色功能"
     */
    private String stripLeadingTrailingStopwords(String seg) {
        String s = seg;
        boolean changed = true;
        // 剥头
        while (changed && !s.isEmpty()) {
            changed = false;
            for (int n = 4; n >= 1; n--) {
                if (s.length() >= n && STOP_WORDS.contains(s.substring(0, n))) {
                    s = s.substring(n);
                    changed = true;
                    break;
                }
            }
        }
        // 剥尾
        changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (int n = 4; n >= 1; n--) {
                if (s.length() >= n && STOP_WORDS.contains(s.substring(s.length() - n))) {
                    s = s.substring(0, s.length() - n);
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    /**
     * 从长片段中提取 3-4 字领域词候选，过滤含虚词的切片。
     */
    private void addDomainTerms(Set<String> bucket, String seg) {
        for (int n : DOMAIN_TERM_LENGTHS) {
            for (int i = 0; i + n <= seg.length(); i++) {
                String s = seg.substring(i, i + n);
                if (STOP_WORDS.contains(s)) continue;
                if (containsAnyStopword(s)) continue;
                if (!isMostlyChinese(s)) continue;
                bucket.add(s);
            }
        }
    }

    private boolean containsAnyStopword(String s) {
        // 检查容易污染的单字虚词
        for (char c : "的了是在和与或及一这那有".toCharArray()) {
            if (s.indexOf(c) >= 0) return true;
        }
        return false;
    }

    private boolean isMostlyChinese(String s) {
        int cn = 0;
        for (char c : s.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) cn++;
        }
        return cn * 2 >= s.length();
    }
}