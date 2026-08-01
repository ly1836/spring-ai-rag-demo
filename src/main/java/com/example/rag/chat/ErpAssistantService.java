package com.example.rag.chat;

import com.example.rag.chat.client.AssistantClientProvider;
import com.example.rag.chat.dto.ChatAnswerResult;
import com.example.rag.chat.dto.ChatStreamFrame;
import com.example.rag.chat.dto.DocSnippet;
import com.example.rag.chat.guard.BusinessDataTurnGuard;
import com.example.rag.chat.lifecycle.AssistantLifecycleService;
import com.example.rag.config.TenantContext;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.registry.ToolSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ERP 统一智能助手服务。
 * <p>
 * 负责 auto、data、knowledge 三种模式的模型调用编排；客户端构建和问答生命周期
 * 分别委托给独立协作服务处理。
 */
@Service
public class ErpAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ErpAssistantService.class);

    /** RAG 向量检索的默认相似度阈值。 */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    /** 知识问答召回数量，覆盖同一主题分布在多个相邻片段的情况。 */
    private static final int KNOWLEDGE_TOP_K = 8;

    /** 知识问答相似度阈值，兼容当前中文技术文档的实际召回分数。 */
    private static final double KNOWLEDGE_SIMILARITY_THRESHOLD = 0.25;

    /** 智能助手 ChatClient 提供器。 */
    private final AssistantClientProvider clientProvider;

    /** 问答生命周期服务。 */
    private final AssistantLifecycleService lifecycleService;

    /** PgVector 向量数据库。 */
    private final VectorStore vectorStore;

    /** Tool 注册服务，用于生成与当前快照一致的示例问题。 */
    private final ToolRegistryService toolRegistryService;

    /** 基于 JDBC 持久化的会话记忆。 */
    private final ChatMemory chatMemory;

    /** 当前轮业务数据守卫。 */
    private final BusinessDataTurnGuard businessDataTurnGuard;

    /** AI 生成的预置示例问题缓存。 */
    private volatile List<String> cachedHints;

    /** 预置示例问题对应的 Tool 快照版本。 */
    private volatile long cachedHintsVersion;

    /**
     * 创建 ERP 统一智能助手服务。
     *
     * @param clientProvider       ChatClient 提供器
     * @param lifecycleService     问答生命周期服务
     * @param vectorStore          向量数据库
     * @param chatMemoryRepository 会话记忆仓库
     * @param toolRegistryService  Tool 注册服务
     * @param businessDataTurnGuard 当前轮业务数据守卫
     */
    public ErpAssistantService(AssistantClientProvider clientProvider,
            AssistantLifecycleService lifecycleService,
            VectorStore vectorStore,
            ChatMemoryRepository chatMemoryRepository,
            ToolRegistryService toolRegistryService,
            BusinessDataTurnGuard businessDataTurnGuard) {
        this.clientProvider = clientProvider;
        this.lifecycleService = lifecycleService;
        this.vectorStore = vectorStore;
        this.toolRegistryService = toolRegistryService;
        this.businessDataTurnGuard = businessDataTurnGuard;
        // 会话窗口大小与原实现保持一致，具体查询上限仍由 JDBC 仓库控制。
        this.chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(20)
            .build();
    }

    /**
     * 自动模式非流式问答，同时启用 Tool Calling 和 RAG。
     *
     * @param question                    用户问题
     * @param conversationId              会话 ID
     * @param modelId                     模型 ID
     * @param requireExistingConversation 是否要求会话已存在
     * @return 文本与可空图表
     */
    public ChatAnswerResult ask(String question, String conversationId, String modelId,
            boolean requireExistingConversation) {
        String mode = "auto";
        String modelName = this.clientProvider.resolveModelName(modelId);
        // 统一在模型调用前校验会话、保存用户消息并检查计费配额。
        this.lifecycleService.prepareConversation(
            conversationId, question, mode, requireExistingConversation);

        // 每轮使用独立 traceId，隔离业务 Tool 结果、图表和调用流水。
        String traceId = this.lifecycleService.createTraceId();
        try {
            long startTime = System.currentTimeMillis();
            // 自动模式保留会话记忆和租户隔离的 RAG 检索能力。
            // 带 Tool 的客户端同时提供业务查询能力和内部图表类型选择能力。
            // ToolContext 为后续 Tool 结果捕获提供 traceId、租户、会话和模型边界。
            // 两个 Advisor 分别补充最近会话消息和当前租户的知识库检索片段。
            // currentTurnQuestion 只增强发送给模型的约束，数据库仍保存用户原始问题。
            ChatResponse response = this.clientProvider.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                        QuestionAnswerAdvisor.builder(this.vectorStore)
                            .searchRequest(this.buildTenantSearchRequest(question))
                            .build()))
                .user(this.businessDataTurnGuard.currentTurnQuestion(question))
                .call()
                .chatResponse();

            // 首次回答缺少当前轮业务数据时最多重试一次，禁止复用历史表格生成答案。
            response = this.businessDataTurnGuard.ensureNonStreaming(response,
                // 重试只替换用户约束，模型、ToolContext、会话记忆和 RAG 条件均保持一致。
                () -> this.clientProvider.resolveClient(modelId).prompt()
                    .options(ChatOptions.builder().model(modelName))
                    .toolContext(this.lifecycleService.buildToolContext(
                        traceId, conversationId, mode, modelName))
                    .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                            QuestionAnswerAdvisor.builder(this.vectorStore)
                                .searchRequest(this.buildTenantSearchRequest(question))
                                .build()))
                    .user(this.businessDataTurnGuard.retryQuestion(question))
                    .call()
                    .chatResponse(),
                mode, question, traceId, TenantContext.requireEntCode(), conversationId);

            // 统一净化最终回答，并完成图表选择、消息持久化和单次计费。
            return this.lifecycleService.finishNonStreaming(
                question, conversationId, mode, modelId, modelName, response,
                System.currentTimeMillis() - startTime, traceId);
        }
        finally {
            // 非流式调用无论成功或异常都立即释放本轮短生命周期数据。
            this.lifecycleService.clearTrace(traceId);
        }
    }

    /**
     * 自动模式流式问答，直接返回最新类型化事件协议。
     *
     * @param question                    用户问题
     * @param conversationId              会话 ID
     * @param modelId                     模型 ID
     * @param requireExistingConversation 是否要求会话已存在
     * @return 类型化事件流
     */
    public Flux<ChatStreamFrame> askStream(String question, String conversationId, String modelId,
            boolean requireExistingConversation) {
        String mode = "auto";
        String modelName = this.clientProvider.resolveModelName(modelId);
        // 流式调用同样先固定会话状态和配额边界，再创建本轮独立链路。
        this.lifecycleService.prepareConversation(
            conversationId, question, mode, requireExistingConversation);
        String traceId = this.lifecycleService.createTraceId();
        // 有当前轮业务结果时保持实时输出；缺失时暂存首次流并最多重试一次。
        // 首次流和重试流共用 traceId，因此 Tool 调用、图表和 Token 可在同一轮统一收口。
        Flux<ChatResponse> responseFlux = this.businessDataTurnGuard.ensureStreaming(
            // 首次调用同时启用会话记忆、租户 RAG 和业务 Tool。
            this.clientProvider.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                        QuestionAnswerAdvisor.builder(this.vectorStore)
                            .searchRequest(this.buildTenantSearchRequest(question))
                            .build()))
                .user(this.businessDataTurnGuard.currentTurnQuestion(question))
                .stream()
                .chatResponse(),
            // 只有守卫确认首次调用没有本轮结构化业务结果时，才订阅该重试 Supplier。
            () -> this.clientProvider.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                        QuestionAnswerAdvisor.builder(this.vectorStore)
                            .searchRequest(this.buildTenantSearchRequest(question))
                            .build()))
                .user(this.businessDataTurnGuard.retryQuestion(question))
                .stream()
                .chatResponse(),
            mode, question, traceId, TenantContext.requireEntCode(), conversationId);
        // 生命周期服务负责转换类型化事件，并统一处理成功、异常和取消收口。
        return this.lifecycleService.recordStream(
            responseFlux, question, conversationId, mode, modelId, modelName, traceId);
    }

    /**
     * 数据模式非流式问答，仅启用业务 Tool Calling。
     *
     * @param question                    用户问题
     * @param conversationId              会话 ID
     * @param modelId                     模型 ID
     * @param requireExistingConversation 是否要求会话已存在
     * @return 文本与可空图表
     */
    public ChatAnswerResult askData(String question, String conversationId, String modelId,
            boolean requireExistingConversation) {
        String mode = "data";
        String modelName = this.clientProvider.resolveModelName(modelId);
        // 数据模式沿用统一的会话保存和配额检查，不启用知识库检索。
        this.lifecycleService.prepareConversation(
            conversationId, question, mode, requireExistingConversation);

        String traceId = this.lifecycleService.createTraceId();
        try {
            long startTime = System.currentTimeMillis();
            // 数据模式只挂载会话记忆，不执行向量检索。
            // 使用带 Tool 客户端查询 ERP 实时数据，并通过 ToolContext 绑定本轮数据边界。
            // 增强问题明确历史消息只能用于理解指代，不能提供当前轮业务数字。
            ChatResponse response = this.clientProvider.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build()))
                .user(this.businessDataTurnGuard.currentTurnQuestion(question))
                .call()
                .chatResponse();

            // 确认回答使用本轮业务 Tool 数据；缺失时执行一次同模型有界重试。
            response = this.businessDataTurnGuard.ensureNonStreaming(response,
                // 数据模式重试继续保留会话记忆，避免丢失客户、订单等上下文指代。
                () -> this.clientProvider.resolveClient(modelId).prompt()
                    .options(ChatOptions.builder().model(modelName))
                    .toolContext(this.lifecycleService.buildToolContext(
                        traceId, conversationId, mode, modelName))
                    .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build()))
                    .user(this.businessDataTurnGuard.retryQuestion(question))
                    .call()
                    .chatResponse(),
                mode, question, traceId, TenantContext.requireEntCode(), conversationId);

            // 图表与文本在同一次生命周期收口中保存和计费。
            return this.lifecycleService.finishNonStreaming(
                question, conversationId, mode, modelId, modelName, response,
                System.currentTimeMillis() - startTime, traceId);
        }
        finally {
            // 清理本轮 Tool 聚合和图表暂存，避免后续请求读取旧数据。
            this.lifecycleService.clearTrace(traceId);
        }
    }

    /**
     * 数据模式流式问答，直接返回最新类型化事件协议。
     *
     * @param question                    用户问题
     * @param conversationId              会话 ID
     * @param modelId                     模型 ID
     * @param requireExistingConversation 是否要求会话已存在
     * @return 类型化事件流
     */
    public Flux<ChatStreamFrame> askDataStream(String question, String conversationId, String modelId,
            boolean requireExistingConversation) {
        String mode = "data";
        String modelName = this.clientProvider.resolveModelName(modelId);
        // 数据模式流式入口先完成会话准备，再建立当前轮 traceId。
        this.lifecycleService.prepareConversation(
            conversationId, question, mode, requireExistingConversation);
        String traceId = this.lifecycleService.createTraceId();
        // 在业务数据来源确认前不发送未验证回答，重试仍复用相同会话和链路上下文。
        // 一旦业务 Tool 已在首个最终回答分片前返回结果，守卫会直接透传原始响应流。
        Flux<ChatResponse> responseFlux = this.businessDataTurnGuard.ensureStreaming(
            // 首次数据查询仅加载会话记忆，不访问向量知识库。
            this.clientProvider.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build()))
                .user(this.businessDataTurnGuard.currentTurnQuestion(question))
                .stream()
                .chatResponse(),
            // 首次调用结束仍无结构化业务结果时，才执行一次延迟创建的重试流。
            () -> this.clientProvider.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build()))
                .user(this.businessDataTurnGuard.retryQuestion(question))
                .stream()
                .chatResponse(),
            mode, question, traceId, TenantContext.requireEntCode(), conversationId);
        // 下游只接收统一的 delta、可选 chart、done 或 error 事件。
        return this.lifecycleService.recordStream(
            responseFlux, question, conversationId, mode, modelId, modelName, traceId);
    }

    /**
     * 知识模式非流式问答，仅启用 RAG，不向模型暴露任何 Tool。
     *
     * @param question                    用户问题
     * @param conversationId              会话 ID
     * @param modelId                     模型 ID
     * @param requireExistingConversation 是否要求会话已存在
     * @return 文本回答，图表固定为空
     */
    public ChatAnswerResult askKnowledge(String question, String conversationId, String modelId,
            boolean requireExistingConversation) {
        String mode = "knowledge";
        String modelName = this.clientProvider.resolveModelName(modelId);
        // knowledge 模式仍复用统一会话和计费流程，但不会进入业务数据守卫。
        this.lifecycleService.prepareConversation(
            conversationId, question, mode, requireExistingConversation);

        // knowledge 模式必须使用不带 Tool 的客户端。
        var noToolsClient = this.clientProvider.resolveKnowledgeClient(modelId);
        String traceId = this.lifecycleService.createTraceId();
        try {
            long startTime = System.currentTimeMillis();
            // noToolsClient 从客户端层移除 Tool，Advisor 参数再关闭自动注册，形成双重隔离。
            // knowledge 模式仍加载会话记忆和租户 RAG，用于连续的产品知识问答。
            ChatResponse response = noToolsClient.prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                        QuestionAnswerAdvisor.builder(this.vectorStore)
                            .searchRequest(this.buildKnowledgeSearchRequest(question))
                            .build()))
                .user(question)
                .call()
                .chatResponse();

            // 统一保存知识回答；由于本轮没有业务 Tool 结果，图表保持为空。
            return this.lifecycleService.finishNonStreaming(
                question, conversationId, mode, modelId, modelName, response,
                System.currentTimeMillis() - startTime, traceId);
        }
        finally {
            // 保持三种模式一致的 trace 清理语义。
            this.lifecycleService.clearTrace(traceId);
        }
    }

    /**
     * 知识模式流式问答，仅启用 RAG 并返回最新类型化事件协议。
     *
     * @param question                    用户问题
     * @param conversationId              会话 ID
     * @param modelId                     模型 ID
     * @param requireExistingConversation 是否要求会话已存在
     * @return 类型化事件流
     */
    public Flux<ChatStreamFrame> askKnowledgeStream(String question, String conversationId,
            String modelId, boolean requireExistingConversation) {
        String mode = "knowledge";
        String modelName = this.clientProvider.resolveModelName(modelId);
        // 先完成会话准备，确保流式异常或取消时也能按统一状态收口。
        this.lifecycleService.prepareConversation(
            conversationId, question, mode, requireExistingConversation);

        // knowledge 模式流式调用同样禁用 Tool 自动注册。
        var noToolsClient = this.clientProvider.resolveKnowledgeClient(modelId);
        String traceId = this.lifecycleService.createTraceId();
        // 直接把无 Tool 的模型响应交给类型化流式生命周期，不执行业务数据重试。
        // 生命周期仍负责最终答案净化、usage 统计以及成功、异常、取消三种终止状态。
        return this.lifecycleService.recordStream(
            noToolsClient.prompt()
                .options(ChatOptions.builder().model(modelName))
                .toolContext(this.lifecycleService.buildToolContext(
                    traceId, conversationId, mode, modelName))
                .advisors(advisor -> advisor
                    .param(ChatMemory.CONVERSATION_ID, conversationId)
                    .param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE)
                    .advisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
                        QuestionAnswerAdvisor.builder(this.vectorStore)
                            .searchRequest(this.buildKnowledgeSearchRequest(question))
                            .build()))
                .user(question)
                .stream()
                .chatResponse(),
            question, conversationId, mode, modelId, modelName, traceId);
    }

    /**
     * 基于当前 Tool 描述生成并缓存预置问题。
     *
     * @return 预置问题列表
     */
    public List<String> generateHints() {
        ToolSnapshot snapshot = this.toolRegistryService.currentSnapshot();
        // 动态 Tool 快照版本未变化时直接复用缓存，避免为提示问题重复调用 LLM。
        if (this.cachedHints != null && this.cachedHintsVersion == snapshot.version()) {
            return this.cachedHints;
        }

        try {
            // Tool 描述仍按当前快照生成，动态 Tool 刷新后缓存会自动失效。
            StringBuilder descriptions = new StringBuilder();
            for (String description : snapshot.descriptions()) {
                descriptions.append("- ").append(description).append("\n");
            }

            // 示例问题生成只依赖 Tool 描述，不允许模型实际执行任何业务查询。
            String response = this.clientProvider.resolveNoToolsClient(null).prompt()
                .advisors(advisor -> advisor
                    .param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE))
                .system("你是一个只输出问题的机器，禁止输出任何解释、前言、总结或编号。直接从第一个问题开始输出。")
                .user("参考以下 ERP 工具能力，生成 4 个简短自然的中文疑问句（每个≤20字），覆盖不同模块，每行一个。\n\n"
                    + "要求：\n"
                    + "- 使用自然疑问句式，如'...有哪些？'、'...是多少？'、'...怎么样？'\n"
                    + "- 禁止用'查询'、'查看'等祈使动词开头\n"
                    + "- 禁止包含具体公司名、产品名、订单号，使用通用表述\n\n"
                    + descriptions
                    + "\n示例格式：\n最近有哪些销售订单？\n库存不足的产品有哪些？\n上月质检合格率多少？\n本月收支情况如何？")
                .call()
                .content();

            if (response == null || response.isBlank()) {
                return getDefaultHints();
            }

            // 后处理规则与原实现保持一致，避免模型解释文本进入提示卡片。
            List<String> hints = Arrays.stream(response.split("\n"))
                .map(String::trim)
                .map(value -> value.replaceFirst("^[\\d.、)）\\-*·]+\\s*", ""))
                .map(value -> value.replaceAll("[\"'“”‘’]", ""))
                .map(String::trim)
                .filter(value -> value.length() <= 30
                    && (value.endsWith("？") || value.endsWith("?")))
                .limit(4)
                .toList();

            if (hints.size() >= 4) {
                this.cachedHints = hints;
                this.cachedHintsVersion = snapshot.version();
            }
            return hints.isEmpty() ? getDefaultHints() : hints;
        }
        catch (Exception ex) {
            log.warn("生成预置问题失败: {}", ex.getMessage());
            return getDefaultHints();
        }
    }

    /**
     * 仅从向量库检索租户隔离的文档片段，不调用 LLM。
     *
     * @param query 检索文本
     * @param topK  返回数量
     * @return 文档片段列表
     */
    public List<DocSnippet> searchDocs(String query, int topK) {
        // 文档搜索是纯向量检索，不进入会话、LLM、Tool Calling 或计费链路。
        return this.vectorStore.similaritySearch(
            this.buildTenantSearchRequest(query, topK, 0.0)).stream()
            .map(document -> new DocSnippet(
                document.getText(),
                (String) document.getMetadata().get("source"),
                document.getScore()))
            .collect(Collectors.toList());
    }

    /**
     * 返回 LLM 生成失败时的静态预置问题。
     *
     * @return 静态预置问题
     */
    private static List<String> getDefaultHints() {
        return List.of(
            "最近有哪些销售订单？",
            "库存不足的产品有哪些？",
            "上月质检合格率多少？",
            "本月收支情况如何？");
    }

    /**
     * 构建带默认数量和阈值的租户隔离向量检索请求。
     *
     * @param query 检索文本
     * @return 向量检索请求
     */
    private SearchRequest buildTenantSearchRequest(String query) {
        return this.buildTenantSearchRequest(query, 5, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * 构建带自定义数量和阈值的租户隔离向量检索请求。
     *
     * @param query     检索文本
     * @param topK      返回数量
     * @param threshold 相似度阈值
     * @return 向量检索请求
     */
    private SearchRequest buildTenantSearchRequest(String query, int topK, double threshold) {
        String entCode = TenantContext.requireEntCode();
        // 将租户条件写入向量检索表达式，避免跨租户召回知识片段。
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.eq("ent_code", entCode).build();

        return SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(threshold)
            .filterExpression(filter)
            .build();
    }

    /**
     * 构建知识问答模式的租户隔离向量检索请求。
     *
     * @param query 检索文本
     * @return 扩大召回范围后的知识检索请求
     */
    private SearchRequest buildKnowledgeSearchRequest(String query) {
        return this.buildTenantSearchRequest(
            query, KNOWLEDGE_TOP_K, KNOWLEDGE_SIMILARITY_THRESHOLD);
    }

}
