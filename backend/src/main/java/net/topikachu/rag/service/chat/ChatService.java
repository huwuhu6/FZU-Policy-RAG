package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.evaluation.ContextNode;
import net.topikachu.rag.evaluation.EvaluationConfig;
import net.topikachu.rag.evaluation.EvaluationResultItem;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chat service with hybrid search (dense + sparse) and reranking.
 */
@Service
@Slf4j
public class ChatService {

    private final RetrievalPipeline retrievalPipeline;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final TracingSupport tracingSupport;
    private final GroundedTurnModule groundedTurnModule;
    private final QueryPreProcessor queryPreProcessor;

    @Value("${rag.retrieval.hybrid-topk:80}")
    private int hybridTopK;

    @Value("${rag.retrieval.rerank-topk:10}")
    private int rerankTopK;

    @Value("${rag.retrieval.max-context-chars:40000}")
    private int maxContextChars;

    public ChatService(RetrievalPipeline retrievalPipeline,
            ChatModelStrategyFactory strategyFactory,
            ReactiveChatGateway reactiveChatGateway,
            TracingSupport tracingSupport,
            GroundedTurnModule groundedTurnModule,
            QueryPreProcessor queryPreProcessor) {
        this.retrievalPipeline = retrievalPipeline;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.tracingSupport = tracingSupport;
        this.groundedTurnModule = groundedTurnModule;
        this.queryPreProcessor = queryPreProcessor;
    }

    public record ChatStreamResponse(Flux<String> flux, List<UsedSource> usedSources) {
    }

    public Mono<List<Document>> retrieveForEvaluation(String query, boolean useSparseSearch, boolean useRerank, int topK) {
        int fetchK = useRerank ? hybridTopK : topK;
        return retrievalPipeline.retrieve(query, fetchK, topK, useSparseSearch, useRerank);
    }

    /**
     * Evaluation-only retrieval entry point with explicit rerank request size
     * and outcome observability. The existing overload remains unchanged for
     * the legacy ecom runner and other callers.
     */
    public Mono<RetrievalPipeline.RetrievalOutcome> retrieveForEvaluationWithOutcome(
            String query,
            boolean useSparseSearch,
            boolean useRerank,
            int topK,
            int evaluationRerankTopK) {
        int fetchK = useRerank ? hybridTopK : topK;
        int rerankRequestTopK = useRerank ? evaluationRerankTopK : topK;
        return retrievalPipeline.retrieveForEvaluation(
                query, fetchK, rerankRequestTopK, useSparseSearch, useRerank);
    }

    // TODO:: 精确计算token消耗量
    public Mono<ChatStreamResponse> streamWithSources(String userInput, String conversationId,
            CurrentUserContext currentUserContext, SearchScope searchScope,
            String modelId, String msgId) {
        String traceId = tracingSupport.getCurrentTraceId();
        RagRequestContext requestContext = RagRequestContext.create(traceId, conversationId, msgId, modelId);

        return queryPreProcessor.process(userInput, conversationId, modelId, requestContext)
                .flatMap(processed -> {
                    GroundedTurnModule.Command command = new GroundedTurnModule.Command(
                            userInput,
                            conversationId,
                            currentUserContext.userId(),
                            modelId,
                            "rag",
                            msgId,
                            traceId,
                            List.of(),
                            List.of());
                    boolean directRoute = switch (processed.route()) {
                        case EXACT, SMALLTALK, CLARIFY, FAQ -> true;
                        case RETRIEVE -> false;
                    };
                    if (directRoute) {
                        String answerType = processed.route() == QueryPreProcessor.PreprocessRoute.FAQ
                                ? "faq"
                                : processed.route() == QueryPreProcessor.PreprocessRoute.CLARIFY
                                ? "clarify"
                                : "chitchat";
                        return groundedTurnModule.commitDirectReply(command, processed.directReply(), answerType)
                                .thenReturn(new ChatStreamResponse(
                                        Flux.just(processed.directReply()),
                                        List.of()))
                                .doOnSuccess(response -> logCompleted(requestContext, processed.route(), answerType, 0));
                    }

                    Map<String, Object> retrievalTags = new java.util.LinkedHashMap<>(requestContext.traceTags());
                    retrievalTags.put("chat.mode", "rag");
                    retrievalTags.put("chat.model_id", modelId == null ? "" : modelId);
                    retrievalTags.put("chat.conversation_id", conversationId == null ? "" : conversationId);
                    return retrievalPipeline.retrieveWithParentContexts(
                                    processed.searchTargetQuery(),
                                    currentUserContext,
                                    searchScope,
                                    hybridTopK,
                                    rerankTopK,
                                    retrievalTags)
                            .flatMap(retrievalResult -> groundedTurnModule.stream(new GroundedTurnModule.Command(
                                    userInput,
                                    conversationId,
                                    currentUserContext.userId(),
                                    modelId,
                                    "rag",
                                    msgId,
                                    traceId,
                                    retrievalResult.childCandidates(),
                                    retrievalResult.parentContexts(),
                                    processed.searchTargetQuery())))
                            .map(result -> new ChatStreamResponse(
                                    result.answerFlux()
                                            .doOnComplete(() -> logCompleted(
                                                    requestContext,
                                                    processed.route(),
                                                    result.answerType(),
                                                    result.usedSources().size())),
                                    result.usedSources()));
                });
    }

    private void logCompleted(RagRequestContext context,
                              QueryPreProcessor.PreprocessRoute route,
                              String answerType,
                              int sourceCount) {
        log.info("[RAG] completed traceId={} conversationId={} msgId={} route={} answerType={} sources={} totalMs={}",
                context.traceId(), context.conversationId(), context.msgId(),
                route == QueryPreProcessor.PreprocessRoute.RETRIEVE ? "RAG" : route,
                answerType, sourceCount,
                context.elapsedMs());
    }

    /**
     * Non-streaming, strict evaluation method used by AblationStudyRunner.
     * Extracts ContextNodes with metadata and applies dynamic evaluation
     * configurations.
     */
    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            String modelId) {
        return evaluateQuery(question, groundTruth, config, baselinePromptResource, optimizedPromptResource, rerankTopK,
                modelId, true);
    }

    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            int topK,
            String modelId) {
        return evaluateQuery(question, groundTruth, config, baselinePromptResource, optimizedPromptResource, topK,
                modelId, true);
    }

    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            int topK,
            String modelId,
            boolean allowModelFallback) {
        long startTime = System.currentTimeMillis();

        return retrieveForEvaluation(question, config.useSparseSearch(), config.useRerank(), topK)
                .flatMap(docs -> Mono.defer(() -> {
                    List<ContextNode> contextNodes = new ArrayList<>();
                    StringBuilder contextTextBuilder = new StringBuilder();

                    for (int i = 0; i < docs.size(); i++) {
                        Document doc = docs.get(i);
                        String fileName = (String) doc.getMetadata().getOrDefault("file_name", "Unknown File");
                        Object scoreObj = doc.getMetadata().get("score");
                        Double score = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;

                        contextNodes.add(new ContextNode(doc.getText(), fileName, score));

                        String structuredEntry = String.format("【文档来源: %s】\n内容: %s\n------------------------\n",
                                fileName, doc.getText());

                        if (contextTextBuilder.length() + structuredEntry.length() > maxContextChars) {
                            break;
                        }
                        contextTextBuilder.append(structuredEntry);
                    }

                    String contextText = contextTextBuilder.toString();
                    String systemPromptText = loadPromptText(config, baselinePromptResource, optimizedPromptResource);

                    return reactiveChatGateway.call(
                                    strategyFactory.getStrategy(modelId).getChatClient(),
                                    systemPromptText,
                                    Map.of("context", contextText, "question", question),
                                    question)
                            .map(generatedAnswer -> {
                                log.info("Evaluation query completed in {}ms. Length of answer: {}",
                                        System.currentTimeMillis() - startTime, generatedAnswer.length());
                                return new EvaluationResultItem(
                                        question,
                                        groundTruth,
                                        generatedAnswer,
                                        contextNodes);
                            });
                })
                .retryWhen(reactor.util.retry.Retry.backoff(3, java.time.Duration.ofSeconds(2))
                        .filter(throwable -> {
                            // Can add specific sub-exceptions if needed, but for now retry on most runtime
                            // exceptions from APIs
                            return throwable instanceof RuntimeException;
                        })
                        .doBeforeRetry(retrySignal -> log.warn("[{}] API error '{}', retrying... ({}/{})",
                                modelId, retrySignal.failure().getMessage(), retrySignal.totalRetriesInARow() + 1, 3)))
                .onErrorResume(e -> {
                    log.error("[{}] API definitively failed after retries: {}", modelId, e.getMessage());
                    if (!allowModelFallback) {
                        return Mono.error(e);
                    }
                    return Mono.just(new EvaluationResultItem(
                            question,
                            groundTruth,
                            "【系统提示】DashScope 模型调用失败，请稍后再试或联系管理员。错误详情: " + e.getMessage(),
                            new ArrayList<>()));
                }));
    }

    private String loadPromptText(EvaluationConfig config, Resource baselinePromptResource,
            Resource optimizedPromptResource) {
        try {
            Resource promptResource = config.useOptimizedPrompt()
                    ? optimizedPromptResource
                    : baselinePromptResource;
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.error("Failed to load prompt template, falling back to default.", e);
            return "Context:\n{context}";
        }
    }

}
