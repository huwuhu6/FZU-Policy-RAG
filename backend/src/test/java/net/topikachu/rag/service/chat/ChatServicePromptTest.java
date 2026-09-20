package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServicePromptTest {

    @Test
    void sourcedAnswerPromptUsesDedicatedJsonContract() {
        String prompt = SourcedAnswerPrompts.jsonPrompt();

        assertTrue(prompt.contains("usedSources 必须是字符串数组"));
        assertTrue(prompt.contains("会话历史只用于理解指代"));
        assertTrue(prompt.contains("最终答案实际采用的 evidenceId"));
        assertTrue(prompt.contains("answerType 输出 factual"));
        assertTrue(prompt.contains("answerType 输出 refusal"));
        assertTrue(prompt.contains("先用一句话给出明确结论"));
        assertTrue(prompt.contains("询问必要的补充信息"));
        assertTrue(prompt.contains("{context}"));
        assertEquals(1, countOccurrences(prompt, "{context}"));
        assertFalse(prompt.contains("JSON 字段固定为"));
        assertFalse(prompt.contains("不要输出对象数组"));
        assertFalse(prompt.contains("原系统要求"));
        assertFalse(prompt.contains("请直接回答"));
    }

    @Test
    void streamedAnswerPromptPutsConclusionBeforeExplanation() {
        String prompt = SourcedAnswerPrompts.answerPrompt();

        assertTrue(prompt.contains("第一句先给出用户最关心的结论"));
        assertTrue(prompt.contains("提出一个简洁、明确的补充问题"));
        assertTrue(prompt.contains("不要为了追问而回避已经能够回答的部分"));
    }

    @Test
    void conversationRoutePromptHidesInternalRouterRole() {
        String prompt = SourcedAnswerPrompts.conversationRoutePrompt();

        assertTrue(prompt.contains("绝不能在用户可见的 directReply 中提及"));
        assertTrue(prompt.contains("不得提及路由器、分类器、模型"));
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int index = text.indexOf(target);
        while (index >= 0) {
            count++;
            index = text.indexOf(target, index + target.length());
        }
        return count;
    }
}
