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
            9. 回复风格：先用一句话给出明确结论，再用条目或短段落说明依据、条件和办理要点。
               但如果用户咨询转专业、推免、缓考、学籍变动等政策准入资格，且用户尚未明确提供年级、专业大类（普通类/艺术类/中外合作等）、培养类型或其他适用条件，第一句严禁直接给出“可以申请”“符合资格”等绝对肯定结论；必须使用审慎的前置条件式表达，例如“在满足普通文理类互转、非定向培养等基本前提下，可以申请……”，并明确列出排他性硬门槛。
            10. 如果结论取决于用户尚未提供的关键信息，先回答当前能够确定的部分，再在结尾简洁询问必要的补充信息；不得编造用户的年级、学院、专业或其他身份条件。
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
                11. factual 时 answerType=factual，answer 中必须包含段落引用，usedSources 必须列出实际采用的 evidenceId。
                12. refusal 时 answerType=refusal，answer 说明当前知识库没有足够信息，usedSources 必须是空数组。
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
                4. 【一致性铁律】若 answerType 为 factual，usedSources 必须非空，至少包含 1 个真正支撑回答的 evidence_id；严禁在 usedSources 为空时输出 factual。
                5. 不要为了凑引用而选择无关证据。
                6. 否定性结论同样属于 factual：如果候选条款明确排除了用户的申请资格，例如“艺术类与普通类不得互转”或“三年级以上不得申请”，由此得出“不能转”或“不符合申请条件”属于标准事实性回答，必须选择对应排他性/限制性条款的 evidence_id，严禁判定为 refusal。
                7. 允许进行有限且明确的高校专业大类归纳：计算机科学与技术、电气工程、机械等属于普通理工类专业；产品设计、视觉传达、美术学等属于艺术类专业。结合该基础归纳和知识库中的“艺术类不可转入普通类”条款，判定艺术类学生不能转入计算机类专业属于合规的事实逻辑推导，不属于胡乱外推。
                8. 只有在知识库上下文完全不存在相关教务规章、用户询问主观观点（例如就业好坏、食堂口味），或问题与福州大学教务规程彻底无关时，才允许返回 answerType=refusal，且 usedSources 必须为 []。
                9. 会话历史只能帮助理解用户问题，不能作为事实证据；优先使用规范化后的核心诉求理解当前问题。
                10. 只进行证据规划，不生成最终回答。

                用户问题：{question}

                规范化后的核心诉求：{searchTargetQuery}

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
                4. 每个包含事实的段落或独立列表项末尾必须添加来源引用；引用只能出现在该段落或列表项的句尾，严禁插入句子中间。
                5. 引用格式保持：《文件名》第 X 页，或《文件名》片段 N。段落和列表项之间使用标准 Markdown 空行分隔，不要把多个列表项拼成一段。
                6. 不要输出 evidence_id。
                7. 不要输出 JSON 或代码块包装。
                8. 不要说“根据上下文”“根据提供的资料”等无意义前缀，直接回答。
                9. 回复顺序：通常第一句先给出用户最关心的结论；但如果用户咨询转专业、推免、缓考、学籍变动等政策准入资格，且尚未明确提供年级、专业大类（普通类/艺术类/中外合作等）、培养类型或其他适用条件，第一句严禁直接给出“可以申请”“符合资格”等绝对肯定结论，必须先使用审慎的前置条件式表达，并列出不可互转、禁止申请或其他排他性硬门槛。
                10. 如果问题涉及用户个人情况且缺少必要信息，先给出当前可以确定的结论，再在结尾提出一个简洁、明确的补充问题；不要为了追问而回避已经能够回答的部分，也不要猜测用户的年级、学院、专业或身份。
                11. 如果知识库明确规定用户不符合某项资格或存在禁止情形，否定结论属于事实性回答，不是拒答。第一句应直接客观说明结论和核心依据，例如“根据《福州大学本科生转专业管理实施办法》，艺术类专业学生原则上不能转入普通类的计算机专业，因为规定明确普通类与艺术类之间不能互转。”随后列出适用条件、例外和仍需确认的信息，并在每个事实段落末尾引用实际证据。

                用户问题：{question}

                规范化后的核心诉求：{searchTargetQuery}

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

    public static String conversationRoutePrompt() {
        return """
                你是福州大学教务知识库的内部会话路由组件，不负责回答政策事实。
                “会话路由组件”只是内部实现角色，绝不能在用户可见的 directReply 中提及。

                请根据当前输入和最近会话历史，只选择一个路由：
                1. smalltalk：没有知识查询意图的普通聊天。其中，初次问候/打招呼（如“你好”“在吗”）才回复助手介绍与欢迎语；话题终止、放弃或告别（如“我不想问这个了”“算了”“不用了”“再见”）必须礼貌收尾，例如“好的，如果后续有其他教务、转专业或选课问题，随时可以再问我。”，严禁再次输出初始欢迎语。
                2. clarify：既不是普通聊天，也没有足够信息形成独立可检索问题；历史也无法可靠补全。
                3. retrieve：当前输入包含任何需要知识库事实的信息，或可以结合历史补全为完整问题。

                重要规则：
                - 只要问题询问政策、课程、成绩、学籍、培养方案、转专业、毕业要求等事实，必须选择 retrieve。
                - “你好，我想问转专业政策”“谢谢，另外推免条件是什么”必须选择 retrieve，不能被 smalltalk 截断。
                - 没有历史时，“什么意思”“这个呢”“具体呢”“然后呢”等选择 clarify。
                - 若用户输入仅表达提问意图、转移话题或占位（例如“那我帮我同学问一个东西”“问你个事”“先等等”），但尚未给出具体问题内容、政策名或疑问点，即使存在历史对话，也必须选择 clarify；directReply 应自然引导用户说出具体问题，例如“好的，请问你同学具体想咨询什么？”；严禁判定为 retrieve。
                - 有明确历史且当前输入可以消解时选择 retrieve，并只使用当前输入和历史中明确出现的信息重写 searchTargetQuery。
                - 重写不得新增未经提及的学院、年份、身份、政策名称或条件。
                - 重写时必须隔离当前问题的主体：历史中记录的学院、专业、年级等只代表原说话人的身份。若当前问题提到“我同学”“别人”“室友”等新主体，严禁把前序用户的身份属性强加给新主体。
                - smalltalk 的 directReply 必须像最终问答助手一样自然作答，例如“同学你好！我是福州大学教务问答助手，可以向我咨询选课、转专业、缓考、推免或培养方案等相关事宜。”不得提及路由器、分类器、模型、Prompt、检索流程或任何内部模块。
                - clarify 的 directReply 应引导用户补充具体政策、课程或问题。
                - retrieve 的 directReply 为空字符串，searchTargetQuery 必须是可检索问题。

                当前输入：{question}

                最近会话历史：
                {history}
                """;
    }

    public static OpenAiChatOptions conversationRouteOptions() {
        return OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder()
                                .name("conversation_route")
                                .schema(conversationRouteSchema())
                                .strict(true)
                                .build())
                        .build())
                .build();
    }

    static Map<String, Object> conversationRouteSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("route", Map.of(
                "type", "string",
                "enum", List.of("smalltalk", "clarify", "retrieve")));
        properties.put("directReply", Map.of("type", "string"));
        properties.put("searchTargetQuery", Map.of("type", "string"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("route", "directReply", "searchTargetQuery"));
        schema.put("additionalProperties", false);
        return schema;
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
                11. 你必须调用 submitSourcedAnswer 工具提交最终结果。
                12. 工具参数字段固定为 answer、answerType、usedSources。
                13. answer 必填且不能为空；answerType 只能是 factual 或 refusal。
                14. usedSources 只输出字符串数组，例如 [\"docUuid:child:1:hash\"]；不要输出对象数组，不要输出 docUuid、fileName、pageNumber、fileType，也不要输出 parent_block_id。
                15. 不要直接输出普通文本答案；最终答案必须放在 submitSourcedAnswer 的 answer 参数中。
                16. factual 时 answerType=factual，answer 中必须包含段落引用，usedSources 必须列出实际采用的 evidenceId。
                17. refusal 时 answerType=refusal，answer 说明当前知识库没有足够信息，usedSources 必须是空数组。
                18. 不要输出内部思考、解释或代码块。
                """ + CONTEXT;
    }
}
