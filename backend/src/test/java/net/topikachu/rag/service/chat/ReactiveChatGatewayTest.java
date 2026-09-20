package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.agent.AgentResolution;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactiveChatGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodeStructuredResponseParsesPureJson() {
        String raw = """
                {"type":"followup","answerMode":"normal","draftAnswer":"","finalInstruction":"","selectedEvidenceIds":["e1"]}
                """;

        AgentResolution resolution = ReactiveChatGateway.decodeStructuredResponse(raw, AgentResolution.class, objectMapper);

        assertEquals("followup", resolution.type());
        assertEquals("normal", resolution.answerMode());
        assertEquals(List.of("e1"), resolution.selectedEvidenceIds());
    }

    @Test
    void decodeStructuredResponseExtractsJsonFromAnalysisAndFence() {
        String raw = """
                基于检索到的证据，我需要继续收口。

                ```json
                {
                  "type":"followup",
                  "answerMode":"normal",
                  "draftAnswer":"",
                  "finalInstruction":"",
                  "selectedEvidenceIds":["e1","e2"]
                }
                ```
                """;

        AgentResolution resolution = ReactiveChatGateway.decodeStructuredResponse(raw, AgentResolution.class, objectMapper);

        assertEquals("followup", resolution.type());
        assertEquals("normal", resolution.answerMode());
        assertEquals(List.of("e1", "e2"), resolution.selectedEvidenceIds());
    }

    @Test
    void decodeStructuredResponseRejectsPlainText() {
        String raw = "你可能是在询问你的高中的名称。";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReactiveChatGateway.decodeStructuredResponse(raw, AgentResolution.class, objectMapper));

        assertEquals("Could not parse structured tool-phase response.", error.getMessage());
    }

    @Test
    void decodeStructuredResponseAcceptsUsedSourceStringArray() {
        String raw = """
                {
                  "answer":"我们的高中叫测试高中。《test.pdf》第 1 页",
                  "answerType":"factual",
                  "usedSources":["ev-1"]
                }
                """;

        SourcedAnswerResult result = ReactiveChatGateway.decodeStructuredResponse(raw, SourcedAnswerResult.class, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-1", result.usedSources().get(0));
    }

    @Test
    void strictSourcedAnswerParsesFactualAndRefusalJson() {
        SourcedAnswerResult factual = ReactiveChatGateway.decodeStrictSourcedAnswer(
                "{\"answer\":\"答案\",\"answerType\":\"factual\",\"usedSources\":[\"ev-1\"]}",
                objectMapper);
        SourcedAnswerResult refusal = ReactiveChatGateway.decodeStrictSourcedAnswer(
                "{\"answer\":\"无法可靠回答\",\"answerType\":\"refusal\",\"usedSources\":[]}",
                objectMapper);

        assertEquals("factual", factual.answerType());
        assertEquals(List.of("ev-1"), factual.usedSources());
        assertEquals("refusal", refusal.answerType());
        assertEquals(List.of(), refusal.usedSources());
    }

    @Test
    void strictSourcedAnswerRejectsInvalidShape() {
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcedAnswer(
                "{\"answer\":\"答案\",\"answerType\":\"unknown\",\"usedSources\":[]}", objectMapper));
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcedAnswer(
                "{\"answer\":\"答案\",\"usedSources\":[]}", objectMapper));
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcedAnswer(
                "{\"answer\":\"答案\",\"answerType\":\"factual\",\"usedSources\":[],\"extra\":true}", objectMapper));
    }

    @Test
    void strictSourcedAnswerRejectsInvalidJsonWithExplicitException() {
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcedAnswer(
                "{not-json", objectMapper));
    }

    @Test
    void strictSourcePlanParsesOnlyAnswerTypeAndEvidenceIds() {
        SourcePlanResult plan = ReactiveChatGateway.decodeStrictSourcePlan(
                "{\"answerType\":\"factual\",\"usedSources\":[\"ev-1\"]}",
                objectMapper);

        assertEquals("factual", plan.answerType());
        assertEquals(List.of("ev-1"), plan.usedSources());
    }

    @Test
    void strictSourcePlanRejectsAnswerFieldAndMalformedShapes() {
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcePlan(
                "{\"answer\":\"不要出现\",\"answerType\":\"factual\",\"usedSources\":[\"ev-1\"]}",
                objectMapper));
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcePlan(
                "{\"answerType\":\"factual\",\"usedSources\":[1]}",
                objectMapper));
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictSourcePlan(
                "{not-json", objectMapper));
    }

    @Test
    void strictSchemaOptionsDeclareJsonSchemaAndStrictMode() {
        var responseFormat = SourcedAnswerPrompts.structuredOutputOptions().getResponseFormat();

        assertEquals(org.springframework.ai.openai.api.ResponseFormat.Type.JSON_SCHEMA, responseFormat.getType());
        assertEquals("sourced_answer", responseFormat.getJsonSchema().getName());
        assertEquals(true, responseFormat.getJsonSchema().getStrict());
        assertDoesNotThrow(() -> responseFormat.getJsonSchema().getSchema().get("required"));
    }

    @Test
    void sourcePlanSchemaDeclaresOnlyPlanningFields() {
        var responseFormat = SourcedAnswerPrompts.sourcePlanOptions().getResponseFormat();

        assertEquals(ResponseFormat.Type.JSON_SCHEMA, responseFormat.getType());
        assertEquals("source_plan", responseFormat.getJsonSchema().getName());
        assertEquals(true, responseFormat.getJsonSchema().getStrict());
        assertEquals(List.of("answerType", "usedSources"),
                responseFormat.getJsonSchema().getSchema().get("required"));
    }

    @Test
    void strictConversationRouteParsesAllRoutes() {
        ConversationRouteResult result = ReactiveChatGateway.decodeStrictConversationRoute(
                "{\"route\":\"retrieve\",\"directReply\":\"\",\"searchTargetQuery\":\"转专业条件\"}",
                objectMapper);

        assertEquals(ConversationRouteResult.Route.RETRIEVE, result.route());
        assertEquals("转专业条件", result.searchTargetQuery());
    }

    @Test
    void strictConversationRouteRejectsInvalidShape() {
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictConversationRoute(
                "{\"route\":\"answer\",\"directReply\":\"x\",\"searchTargetQuery\":\"\"}",
                objectMapper));
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictConversationRoute(
                "{\"route\":\"smalltalk\",\"directReply\":\"x\"}", objectMapper));
        assertThrows(StructuredAnswerException.class, () -> ReactiveChatGateway.decodeStrictConversationRoute(
                "{\"route\":\"smalltalk\",\"directReply\":\"x\",\"searchTargetQuery\":\"\",\"extra\":true}",
                objectMapper));
    }

    @Test
    void conversationRouteSchemaIsStrictAndClosed() {
        var responseFormat = SourcedAnswerPrompts.conversationRouteOptions().getResponseFormat();

        assertEquals(ResponseFormat.Type.JSON_SCHEMA, responseFormat.getType());
        assertEquals("conversation_route", responseFormat.getJsonSchema().getName());
        assertEquals(true, responseFormat.getJsonSchema().getStrict());
        assertEquals(false, responseFormat.getJsonSchema().getSchema().get("additionalProperties"));
    }

    @Test
    void joinsStreamingFragmentsInOrder() {
        String json = ReactiveChatGateway.joinStreamChunks(List.of(
                "{\"ans", "wer\":\"abc\",", "\"answerType\":\"factual\",",
                "\"usedSources\":[\"id1\"]}"));

        assertEquals("{\"answer\":\"abc\",\"answerType\":\"factual\",\"usedSources\":[\"id1\"]}", json);
    }

    @Test
    void decodeSourcedAnswerToolCallParsesArguments() {
        OpenAiApi.ChatCompletion completion = sourcedAnswerCompletion(
                ReactiveChatGateway.SUBMIT_SOURCED_ANSWER_TOOL,
                """
                        {"answer":"我们的高中叫测试高中。《test.pdf》第 1 页","answerType":"factual","usedSources":["ev-1"]}
                        """);

        SourcedAnswerResult result = ReactiveChatGateway.decodeSourcedAnswerToolCall(completion, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-1", result.usedSources().get(0));
    }

    @Test
    void sourcedAnswerToolUsesValidFunctionName() {
        OpenAiApi.FunctionTool tool = ReactiveChatGateway.sourcedAnswerTool();

        assertEquals(ReactiveChatGateway.SUBMIT_SOURCED_ANSWER_TOOL, tool.getFunction().getName());
    }

    @Test
    void decodeSourcedAnswerToolCallRejectsMissingToolCall() {
        OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                "plain answer",
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        OpenAiApi.ChatCompletion completion = completion(message);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReactiveChatGateway.decodeSourcedAnswerToolCall(completion, objectMapper));

        assertEquals("Missing structured sourced answer tool call.", error.getMessage());
    }

    @Test
    void decodeSourcedAnswerToolCallRejectsUnexpectedToolName() {
        OpenAiApi.ChatCompletion completion = sourcedAnswerCompletion(
                "otherTool",
                """
                        {"answer":"answer","answerType":"factual","usedSources":["ev-1"]}
                        """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReactiveChatGateway.decodeSourcedAnswerToolCall(completion, objectMapper));

        assertEquals("Unexpected structured sourced answer tool call: otherTool", error.getMessage());
    }

    @Test
    void decodeSourcedAnswerToolCallRejectsInvalidArgumentsJson() {
        OpenAiApi.ChatCompletion completion = sourcedAnswerCompletion(
                ReactiveChatGateway.SUBMIT_SOURCED_ANSWER_TOOL,
                "{bad json");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReactiveChatGateway.decodeSourcedAnswerToolCall(completion, objectMapper));

        assertEquals("Could not parse structured sourced answer tool call.", error.getMessage());
    }

    @Test
    void decodeWithThinkTags() {
        String raw = """
                <think>
                The user is asking about their high school name. Let me check the context...
                I found the answer in the knowledge base.
                </think>
                {"answer":"我们的高中叫测试高中。《test.pdf》第 1 页","answerType":"factual","usedSources":["ev-1"]}
                """;

        SourcedAnswerResult result = ReactiveChatGateway.decodeStructuredResponse(raw, SourcedAnswerResult.class, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-1", result.usedSources().get(0));
    }

    @Test
    void decodeWithThinkTagsAndMarkdownFence() {
        String raw = """
                <think>
                Let me analyze the context to find the answer.
                The relevant information is in evidence ev-2.
                </think>
                Some explanation text here.
                ```json
                {
                  "answer": "根据文档，学校名称是示范高中。《handbook.pdf》第 3 页",
                  "answerType": "factual",
                  "usedSources": ["ev-2"]
                }
                ```
                """;

        SourcedAnswerResult result = ReactiveChatGateway.decodeStructuredResponse(raw, SourcedAnswerResult.class, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-2", result.usedSources().get(0));
    }

    @Test
    void stripThinkTagsRemovesThinkBlocks() {
        String input = "<think>\nsome reasoning\n</think>\n{\"answer\":\"hello\"}";
        String result = ReactiveChatGateway.stripThinkTags(input);
        assertEquals("{\"answer\":\"hello\"}", result);
    }

    @Test
    void stripThinkTagsPreservesNonThinkContent() {
        String input = "{\"answer\":\"hello\",\"answerType\":\"factual\"}";
        String result = ReactiveChatGateway.stripThinkTags(input);
        assertEquals(input, result);
    }

    @Test
    void stripThinkTagsHandlesNull() {
        assertNull(ReactiveChatGateway.stripThinkTags(null));
    }

    private OpenAiApi.ChatCompletion sourcedAnswerCompletion(String toolName, String arguments) {
        OpenAiApi.ChatCompletionMessage.ToolCall toolCall = new OpenAiApi.ChatCompletionMessage.ToolCall(
                "call-1",
                "function",
                new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(toolName, arguments));
        OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                "",
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                null,
                null,
                List.of(toolCall),
                null,
                null,
                null,
                null);
        return completion(message);
    }

    private OpenAiApi.ChatCompletion completion(OpenAiApi.ChatCompletionMessage message) {
        return new OpenAiApi.ChatCompletion(
                "chatcmpl-test",
                List.of(new OpenAiApi.ChatCompletion.Choice(
                        OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS,
                        0,
                        message,
                        null)),
                0L,
                "deepseek-chat",
                null,
                null,
                "chat.completion",
                null);
    }
}
