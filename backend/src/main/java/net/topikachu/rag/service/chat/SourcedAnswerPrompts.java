package net.topikachu.rag.service.chat;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourcedAnswerPrompts {

    private static final String GROUNDING_RULES = """
            你是一个专业的“校园智能知识库问答助手”。你必须基于【知识库上下文】回答。

            必须遵守：
            1. 只能使用【知识库上下文】中的事实，不得编造或外推。
            2. 会话历史只用于理解指代和用户意图，不属于可引用的知识证据。
            3. 如果知识库证据不足，answerType 输出 refusal，answer 简洁说明无法可靠回答，usedSources 输出 []。
            4. 如果输出事实性回答，answerType 输出 factual，usedSources 至少包含一个来源。
            5. 每个事实段落或列表项末尾必须带引用，格式为《文件名》第 X 页；没有页码时用《文件名》片段 N。
            6. 每个事实段落或列表项最多展示 2 个引用。
            7. usedSources 必须是字符串数组；每个字符串都必须来自上下文“可引用 evidence_id”，不能创造新的 evidenceId。
            8. usedSources 只能列出最终答案实际采用的 evidenceId，不得直接复制全部候选或 Agent 工具阶段选中的 ID。
            """;

    private static final String CONTEXT = """

            ================ 知识库上下文 ================
            {context}
            ============================================
            """;

    private SourcedAnswerPrompts() {
    }

    public static String jsonPrompt() {
        return GROUNDING_RULES + """
                9. factual 时 answerType=factual，answer 中必须包含段落引用，usedSources 必须列出实际采用的 evidenceId。
                10. refusal 时 answerType=refusal，answer 说明当前知识库没有足够信息，usedSources 必须是空数组。
                """ + CONTEXT;
    }

    public static String sourcePlanPrompt() {
        return """
                你是知识库证据选择器，不负责生成最终答案。

                任务：
                根据用户问题和候选证据，判断当前证据是否足以可靠回答。

                规则：
                1. 只能选择候选证据中明确出现的 evidence_id。
                2. 不得创造 evidence_id。
                3. factual：必须至少选择 1 个真正支持最终回答的 evidence_id。
                4. 不要为了凑引用而选择无关证据。
                5. refusal：当现有证据不足以可靠回答时，usedSources 必须为 []。
                6. 会话历史只能帮助理解用户问题，不能作为事实证据。
                7. 只进行证据规划，不生成最终回答。

                用户问题：{question}

                ================ 候选证据 ================
                {context}
                ============================================
                """;
    }

    public static String answerPrompt() {
        return """
                你是福州大学教务知识库问答助手。

                下面提供的知识库内容已经经过检索和证据筛选。

                要求：
                1. 只能根据提供的知识库上下文回答，不得使用外部知识补充。
                2. 不得引用未提供的来源。
                3. 回答必须直接回答用户问题，不要解释检索过程、证据选择过程或内部机制。
                4. 每个包含事实的段落或列表项末尾必须添加来源引用。
                5. 引用格式保持： 《文件名》第 X 页，或《文件名》片段 N。
                6. 不要输出 evidence_id。
                7. 不要输出 JSON 或代码块包装。
                8. 不要说“根据上下文”“根据提供的资料”等无意义前缀，直接回答。

                用户问题：{question}

                ================ 已验证知识库上下文 ================
                {context}
                ================================================
                """;
    }

    /**
     * JSON Schema is carried by the OpenAI-compatible request. The prompt
     * remains responsible for grounding rules, not wire-format instructions.
     */
    public static OpenAiChatOptions structuredOutputOptions() {
        return OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder()
                                .name("sourced_answer")
                                .schema(sourcedAnswerSchema())
                                .strict(true)
                                .build())
                        .build())
                .build();
    }

    public static OpenAiChatOptions sourcePlanOptions() {
        return OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder()
                                .name("source_plan")
                                .schema(sourcePlanSchema())
                                .strict(true)
                                .build())
                        .build())
                .build();
    }

    static Map<String, Object> sourcePlanSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answerType", Map.of(
                "type", "string",
                "enum", List.of("factual", "refusal")));
        properties.put("usedSources", Map.of(
                "type", "array",
                "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("answerType", "usedSources"));
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> sourcedAnswerSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answer", Map.of("type", "string"));
        properties.put("answerType", Map.of(
                "type", "string",
                "enum", List.of("factual", "refusal")));
        properties.put("usedSources", Map.of(
                "type", "array",
                "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("answer", "answerType", "usedSources"));
        schema.put("additionalProperties", false);
        return schema;
    }

    public static String toolPrompt() {
        return GROUNDING_RULES + """
                9. 你必须调用 submitSourcedAnswer 工具提交最终结果。
                10. 工具参数字段固定为 answer、answerType、usedSources。
                11. answer 必填且不能为空；answerType 只能是 factual 或 refusal。
                12. usedSources 只输出字符串数组，例如 [\"docUuid:child:1:hash\"]；不要输出对象数组，不要输出 docUuid、fileName、pageNumber、fileType，也不要输出 parent_block_id。
                13. 不要直接输出普通文本答案；最终答案必须放在 submitSourcedAnswer 的 answer 参数中。
                14. factual 时 answerType=factual，answer 中必须包含段落引用，usedSources 必须列出实际采用的 evidenceId。
                15. refusal 时 answerType=refusal，answer 说明当前知识库没有足够信息，usedSources 必须是空数组。
                16. 不要输出内部思考、解释或代码块。
                """ + CONTEXT;
    }
}
