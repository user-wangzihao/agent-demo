package com.wzh.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.wzh.config.DashScopeConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识提取服务
 *
 * 【方案二核心】调用大模型对功能文档做深度分析，提取出：
 * 1. 前置条件与操作之间的因果关系
 * 2. 错误现象与解决方法的对应关系
 * 3. 操作步骤的前后依赖关系
 * 4. 注意事项和易错点
 *
 * 提取结果会被构建为独立的"关联 chunk"存入 Milvus，
 * 大幅提升报错排查类、前置条件类问题的检索命中率。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeExtractService {

    private final DashScopeConfig dashScopeConfig;

    /**
     * 从功能文档的完整文本中提取结构化的因果关系知识
     *
     * @param featureName   功能名称
     * @param fullDocText   整个功能文档所有板块的拼接文本
     * @return 提取出的结构化知识条目列表
     */
    public List<ExtractedKnowledge> extractKnowledge(String featureName, String fullDocText) {
        try {
            String prompt = buildExtractionPrompt(featureName, fullDocText);

            List<Message> messages = new ArrayList<>();
            messages.add(Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content("你是一个专业的知识工程师，擅长从软件产品文档中提取结构化的知识。你需要仔细分析文档内容，找出其中隐含的因果关系、前置条件、错误处理等关键知识点。")
                    .build());
            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build());

            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model(dashScopeConfig.getChatModel())
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .topP(0.3)          // 低随机性，确保提取结果稳定
                    .temperature(0.2f)  // 低温度，减少发散
                    .maxTokens(4000)    // 给足空间
                    .build();

            Generation generation = new Generation();
            GenerationResult result = generation.call(param);

            String response = result.getOutput().getChoices().get(0).getMessage().getContent();
            log.info("知识提取完成 [{}]，原始响应长度: {} 字符", featureName, response.length());

            // 解析大模型返回的结构化知识
            List<ExtractedKnowledge> knowledgeList = parseExtractionResult(response, featureName);
            log.info("文档 [{}] 共提取到 {} 条结构化知识", featureName, knowledgeList.size());

            return knowledgeList;

        } catch (Exception e) {
            log.error("知识提取失败 [{}]: {}", featureName, e.getMessage(), e);
            // 知识提取失败不阻断学习流程，返回空列表
            return new ArrayList<>();
        }
    }

    /**
     * 构建知识提取的 Prompt
     * 引导大模型从文档中提取四类关键知识
     */
    private String buildExtractionPrompt(String featureName, String fullDocText) {
        // 文档过长时截取（控制在3000字以内，避免超出上下文限制）
        String docText = fullDocText.length() > 3000 ?
                fullDocText.substring(0, 3000) + "\n...(文档内容过长，已截取前3000字)" : fullDocText;

        return String.format("""
                请仔细分析以下「%s」功能的文档内容，从中提取关键知识点。
                
                === 文档内容 ===
                %s
                === 文档结束 ===
                
                请从文档中提取以下四类知识，每条知识用指定的标签格式输出：
                
                **第一类：错误与解决方案**
                找出文档中提到的所有错误提示、报错信息、异常情况，以及对应的原因和解决方法。
                格式：
                [ERROR_SOLUTION]
                错误现象：{用户会看到的具体错误提示或异常表现}
                错误原因：{导致这个错误的根本原因}
                解决方法：{具体的解决步骤}
                预防措施：{如何避免再次出现这个错误}
                [/ERROR_SOLUTION]
                
                **第二类：前置条件**
                找出使用某个功能或执行某个操作之前必须满足的条件。
                格式：
                [PREREQUISITE]
                操作：{要执行的操作或功能}
                前置条件：{必须先完成的操作或必须满足的条件}
                未满足时的后果：{如果不满足前置条件会发生什么}
                [/PREREQUISITE]
                
                **第三类：操作注意事项**
                找出文档中提到的容易出错、容易遗漏、需要特别注意的操作要点。
                格式：
                [CAUTION]
                操作场景：{在什么情况下需要注意}
                注意事项：{具体需要注意什么}
                错误做法：{如果不注意会怎样}
                正确做法：{应该怎么做}
                [/CAUTION]
                
                **第四类：操作依赖关系**
                找出操作步骤之间的先后依赖，即必须先做A才能做B的关系。
                格式：
                [DEPENDENCY]
                步骤顺序：{先做什么 → 再做什么 → 最后做什么}
                说明：{为什么必须按这个顺序}
                [/DEPENDENCY]
                
                提取要求：
                1. 只提取文档中明确提到或可以直接推断出的知识，不要编造
                2. 每一类至少尝试提取，如果文档中确实没有相关内容，可以跳过该类
                3. 错误现象要尽量包含用户可能看到的具体提示文字
                4. 解决方法要具体可执行，不要泛泛而谈
                5. 如果同一个知识点属于多个类别，可以重复出现
                
                请现在开始提取：
                """, featureName, docText);
    }

    /**
     * 解析大模型返回的结构化知识
     * 按标签提取四类知识，每条知识转为一个 ExtractedKnowledge 对象
     */
    private List<ExtractedKnowledge> parseExtractionResult(String response, String featureName) {
        List<ExtractedKnowledge> knowledgeList = new ArrayList<>();

        // 提取 [ERROR_SOLUTION] 类型
        extractByTag(response, "ERROR_SOLUTION", "error_solution", featureName, knowledgeList);
        // 提取 [PREREQUISITE] 类型
        extractByTag(response, "PREREQUISITE", "prerequisite", featureName, knowledgeList);
        // 提取 [CAUTION] 类型
        extractByTag(response, "CAUTION", "caution", featureName, knowledgeList);
        // 提取 [DEPENDENCY] 类型
        extractByTag(response, "DEPENDENCY", "dependency", featureName, knowledgeList);

        return knowledgeList;
    }

    /**
     * 按标签名从响应文本中提取知识条目
     */
    private void extractByTag(String response, String tagName, String knowledgeType,
                              String featureName, List<ExtractedKnowledge> knowledgeList) {
        // 正则匹配 [TAG]...[/TAG] 之间的内容
        String regex = "\\[" + tagName + "\\](.*?)\\[/" + tagName + "\\]";
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);

        while (matcher.find()) {
            String content = matcher.group(1).trim();
            if (!content.isEmpty()) {
                ExtractedKnowledge knowledge = new ExtractedKnowledge();
                knowledge.setType(knowledgeType);
                knowledge.setFeatureName(featureName);
                knowledge.setContent(content);
                knowledgeList.add(knowledge);
            }
        }
    }

    // ==================== 数据类 ====================

    @Data
    public static class ExtractedKnowledge {
        /** 知识类型：error_solution / prerequisite / caution / dependency */
        private String type;
        /** 所属功能名称 */
        private String featureName;
        /** 结构化知识内容（原始文本，包含字段标签） */
        private String content;
    }
}