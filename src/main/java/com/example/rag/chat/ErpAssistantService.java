package com.example.rag.chat;

import com.example.rag.billing.BillingService;
import com.example.rag.config.TenantContext;
import com.example.rag.config.TenantContextAccessor;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.registry.ToolRegistryService;
import com.example.rag.tool.registry.ToolSnapshot;
import com.example.rag.tool.trace.ToolCallLogService;
import com.example.rag.tool.trace.ToolCallRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * ERP 统一智能助手服务。
 * <p>
 * 同时整合了两大 AI 能力，LLM 根据用户提问自动选择使用哪种：
 * <ul>
 *   <li>Tool Calling — 调用 8 大 ERP 模块的 @Tool 方法，实时查询 MySQL 业务数据</li>
 *   <li>RAG — 从 PgVector 检索产品手册内容，为 LLM 提供知识上下文</li>
 * </ul>
 * <p>
 * 支持三种问答模式（auto / data / knowledge），每种模式均提供非流式和流式两个版本。
 * 支持多模型切换，通过 modelId 路由到不同 provider 的 ChatClient。
 */
@Service
public class ErpAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ErpAssistantService.class);

    /** RAG 向量检索的相似度阈值，过低匹配到无关文档，过高则漏召回 */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    /** 所有模式共用的系统提示词，定义 LLM 的角色、能力、回答规则和输出格式 */
    private static final String SYSTEM_PROMPT = """
            你是一个制造业ERP系统的智能助手，可以帮助用户查询业务数据和产品知识。
            
            ## 能力
            1. **查询业务数据**：调用工具查询销售、采购、委外、生产、质检、仓库、售后、财务模块的实时数据。
            2. **产品知识问答**：基于上下文中提供的产品手册内容回答问题。
            
            ## 回答规则
            - 涉及具体数字和数据时，必须通过工具查询，不要编造数据
            - 可以同时调用多个工具来综合回答复杂问题
            - 如果信息不足以回答，请如实告知
            - 上下文中可能包含系统自动检索的产品手册片段，如果这些内容与用户问题无关，直接忽略，不要提及或解释它们

            ## 工具选择规则
            - 当动态数据库 Tool 与代码内置 Tool 能力重叠时，优先选择动态数据库 Tool
            
            ## 格式要求（非常重要，必须严格遵守）
            你的回答将通过 Markdown 渲染器展示，请务必使用 Markdown 格式化输出：
            - **多条数据**时，使用 Markdown 表格展示（含表头和对齐分隔符 `|---|`）
            - **单条数据**时，使用列表逐字段展示
            - 关键数字和状态用 **加粗** 标注
            - 用简短的总结开头，数据详情紧随其后
            - 段落之间用空行分隔，确保排版清晰
            """;

    /** 多模型注册中心，按 modelId 路由到对应 provider 的 ChatModel */
    private final ModelRegistry modelRegistry;

    /** 默认 ChatClient.Builder，用于按 Tool 快照重新构建默认 provider 客户端 */
    private final ChatClient.Builder chatClientBuilder;

    /** PgVector 向量数据库，存储产品手册的向量嵌入 */
    private final VectorStore vectorStore;

    /** 对话记录服务，负责会话和消息的持久化 */
    private final ChatHistoryService chatHistoryService;

    /** 计费服务，负责配额校验、token 扣费和流水记录 */
    private final BillingService billingService;

    /** Tool 注册服务，提供代码 Tool + 数据库动态 Tool 的当前快照 */
    private final ToolRegistryService toolRegistryService;

    /** Tool 调用聚合记录器，用于写入助手消息上的 tool_calls */
    private final ToolCallRecorder toolCallRecorder;

    /** Tool 调用流水服务，用于回填助手消息 ID */
    private final ToolCallLogService toolCallLogService;

    /** 会话记忆：基于 a_chat_message 表的 JDBC 存储，内存零占用，重启不丢失 */
    private final ChatMemory chatMemory;

    /** 非默认 provider → 带 system prompt + tools 的 ChatClient 缓存（懒加载） */
    private final Map<String, ChatClient> providerClientCache = new ConcurrentHashMap<>();

    /** provider → 不带 tools 的 ChatClient 缓存（knowledge / hints 使用） */
    private final Map<String, ChatClient> providerNoToolsClientCache = new ConcurrentHashMap<>();

    /** AI 生成的预置示例问题缓存，首次请求后缓存 */
    private volatile List<String> cachedHints;

    /** 预置示例问题对应的 Tool 快照版本 */
    private volatile long cachedHintsVersion;

    /**
     * 构造函数：注入所有依赖，构建默认 ChatClient。
     * <p>
     * chatClientBuilder 由 Spring AI 自动配置注入，基于 @Primary 的 DeepSeek ChatModel。
     * 通过 ToolRegistryService 获取当前 Tool 快照，并按快照版本懒加载 ChatClient。
     */
    public ErpAssistantService(ChatClient.Builder chatClientBuilder,
                               ModelRegistry modelRegistry,
                               VectorStore vectorStore,
                               ChatHistoryService chatHistoryService,
                               BillingService billingService,
                               ChatMemoryRepository chatMemoryRepository,
                               ToolRegistryService toolRegistryService,
                               ToolCallRecorder toolCallRecorder,
                               ToolCallLogService toolCallLogService) {
        this.chatClientBuilder = chatClientBuilder;
        this.modelRegistry = modelRegistry;
        this.vectorStore = vectorStore;
        this.chatHistoryService = chatHistoryService;
        this.billingService = billingService;
        this.toolRegistryService = toolRegistryService;
        this.toolCallRecorder = toolCallRecorder;
        this.toolCallLogService = toolCallLogService;
        // 基于 JDBC 的会话记忆，maxMessages 由 JdbcChatMemoryRepository 的 SQL LIMIT 控制
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    // ==================== 自动模式（Tool Calling + RAG） ====================

    /** 自动模式问答（非流式）：LLM 同时拥有 Tool Calling 和 RAG 能力，自行判断调用哪个 */
    public String ask(String question, String conversationId, String modelId, boolean requireExistingConversation) {
        String mode = "auto";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode, requireExistingConversation);

        String traceId = this.toolCallRecorder.createTraceId();
        try {
            long startTime = System.currentTimeMillis();
            // 挂载会话记忆 + RAG 检索两个 Advisor，并向 ToolCallback 传递链路上下文
            ChatResponse response = this.resolveClient(modelId).prompt()
                    .options(ChatOptions.builder().model(modelName))
                    .toolContext(this.buildToolContext(traceId, conversationId, mode, modelName))
                    .advisors(advisor -> advisor
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                    QuestionAnswerAdvisor.builder(vectorStore)
                                            .searchRequest(this.buildTenantSearchRequest(question))
                                            .build()))
                    .user(question)
                    .call()
                    .chatResponse();

            return this.recordAndReturn(conversationId, mode, modelName, response,
                    System.currentTimeMillis() - startTime, traceId);
        } finally {
            this.toolCallRecorder.clear(traceId);
        }
    }

    /** 自动模式问答（流式 SSE）：功能同 ask()，以逐 chunk 方式返回 */
    public Flux<String> askStream(String question, String conversationId, String modelId, boolean requireExistingConversation) {
        String mode = "auto";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode, requireExistingConversation);

        String traceId = this.toolCallRecorder.createTraceId();
        return this.streamWithRecording(
                this.resolveClient(modelId).prompt()
                        .options(ChatOptions.builder().model(modelName))
                        .toolContext(this.buildToolContext(traceId, conversationId, mode, modelName))
                        .advisors(advisor -> advisor
                                .param(ChatMemory.CONVERSATION_ID, conversationId)
                                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                        QuestionAnswerAdvisor.builder(vectorStore)
                                                .searchRequest(this.buildTenantSearchRequest(question))
                                                .build()))
                        .user(question)
                        .stream().chatResponse(),
                conversationId, mode, modelName, traceId);
    }

    // ==================== 纯数据模式（仅 Tool Calling） ====================

    /** 数据模式问答（非流式）：仅通过 Tool Calling 查询 ERP 数据库，不检索产品手册 */
    public String askData(String question, String conversationId, String modelId, boolean requireExistingConversation) {
        String mode = "data";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode, requireExistingConversation);

        String traceId = this.toolCallRecorder.createTraceId();
        try {
            long startTime = System.currentTimeMillis();
            // 仅挂载会话记忆 Advisor，不挂载 RAG Advisor
            ChatResponse response = this.resolveClient(modelId).prompt()
                    .options(ChatOptions.builder().model(modelName))
                    .toolContext(this.buildToolContext(traceId, conversationId, mode, modelName))
                    .advisors(advisor -> advisor
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build()))
                    .user(question)
                    .call()
                    .chatResponse();

            return this.recordAndReturn(conversationId, mode, modelName, response,
                    System.currentTimeMillis() - startTime, traceId);
        } finally {
            this.toolCallRecorder.clear(traceId);
        }
    }

    /** 数据模式问答（流式 SSE） */
    public Flux<String> askDataStream(String question, String conversationId, String modelId, boolean requireExistingConversation) {
        String mode = "data";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode, requireExistingConversation);

        String traceId = this.toolCallRecorder.createTraceId();
        return this.streamWithRecording(
                this.resolveClient(modelId).prompt()
                        .options(ChatOptions.builder().model(modelName))
                        .toolContext(this.buildToolContext(traceId, conversationId, mode, modelName))
                        .advisors(advisor -> advisor
                                .param(ChatMemory.CONVERSATION_ID, conversationId)
                                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build()))
                        .user(question)
                        .stream().chatResponse(),
                conversationId, mode, modelName, traceId);
    }

    // ==================== 纯知识模式（仅 RAG） ====================

    /** 知识模式问答（非流式）：仅检索产品手册，禁用 Tool Calling */
    public String askKnowledge(String question, String conversationId, String modelId, boolean requireExistingConversation) {
        String mode = "knowledge";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode, requireExistingConversation);

        // 通过不带 tools 的 ChatClient 调用知识模式，禁止 LLM 调用 ERP 工具
        var noToolsClient = this.resolveNoToolsClient(modelId);

        String traceId = this.toolCallRecorder.createTraceId();
        try {
            long startTime = System.currentTimeMillis();
            ChatResponse response = noToolsClient.prompt()
                    .options(ChatOptions.builder().model(modelName))
                    .toolContext(this.buildToolContext(traceId, conversationId, mode, modelName))
                    .advisors(advisor -> advisor
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE)
                            .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                    QuestionAnswerAdvisor.builder(vectorStore)
                                            .searchRequest(this.buildTenantSearchRequest(question))
                                            .build()))
                    .user(question)
                    .call()
                    .chatResponse();

            return this.recordAndReturn(conversationId, mode, modelName, response,
                    System.currentTimeMillis() - startTime, traceId);
        } finally {
            this.toolCallRecorder.clear(traceId);
        }
    }

    /** 知识模式问答（流式 SSE） */
    public Flux<String> askKnowledgeStream(String question, String conversationId, String modelId, boolean requireExistingConversation) {
        String mode = "knowledge";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode, requireExistingConversation);

        // 通过不带 tools 的 ChatClient 调用知识模式，禁止 LLM 调用 ERP 工具
        var noToolsClient = this.resolveNoToolsClient(modelId);

        String traceId = this.toolCallRecorder.createTraceId();
        return this.streamWithRecording(
                noToolsClient.prompt()
                        .options(ChatOptions.builder().model(modelName))
                        .toolContext(this.buildToolContext(traceId, conversationId, mode, modelName))
                        .advisors(advisor -> advisor
                                .param(ChatMemory.CONVERSATION_ID, conversationId)
                                .param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE)
                                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                        QuestionAnswerAdvisor.builder(vectorStore)
                                                .searchRequest(this.buildTenantSearchRequest(question))
                                                .build()))
                        .user(question)
                        .stream().chatResponse(),
                conversationId, mode, modelName, traceId);
    }

    // ==================== 模型路由 ====================

    /**
     * 根据 modelId 获取带 system prompt + tools 的 ChatClient。
     * <p>
     * 不同 provider 对应不同的 ChatModel（API endpoint + auth），不能仅通过 ChatOptions 切换。
     * 因此为每个 provider 构建独立的 ChatClient 并缓存，确保请求发到正确的 API。
     *
     * @param modelId 前端传入的模型 ID（如 deepseek-chat / qwen-max / gemini-2.0-flash）
     * @return 对应 provider 的 ChatClient（已装配 system prompt + tools）
     */
    private ChatClient resolveClient(String modelId) {
        ToolSnapshot snapshot = this.toolRegistryService.currentSnapshot();
        var item = this.modelRegistry.getModelItem(modelId);
        String provider = item != null ? item.getProvider() : "default";
        var defaultItem = this.modelRegistry.getModelItem(null);
        boolean defaultProvider = item == null || (defaultItem != null && provider.equals(defaultItem.getProvider()));
        String cacheKey = provider + ":" + snapshot.version();

        // 按 provider + Tool 快照版本缓存，确保刷新后新请求使用最新 Tool
        ChatClient client = this.providerClientCache.computeIfAbsent(cacheKey, p -> {
            ChatModel chatModel = this.modelRegistry.getChatModel(modelId);
            ChatClient.Builder builder = (!defaultProvider && chatModel != null)
                    ? ChatClient.builder(chatModel)
                    : this.chatClientBuilder.clone();
            // 用当前 Tool 快照构建完整 ChatClient
            return builder
                    .defaultSystem(SYSTEM_PROMPT)
                    .defaultTools(snapshot.callbacks().toArray())
                    .build();
        });
        clearOlderProviderClientCache(provider, snapshot.version());
        return client;
    }

    /**
     * 根据 modelId 解析实际传给 API 的模型名称。
     *
     * @param modelId 前端传入的模型 ID
     * @return 实际模型名称（如 deepseek-chat / qwen-max），用于 ChatOptions 和计费记录
     */
    private String resolveModelName(String modelId) {
        var item = this.modelRegistry.getModelItem(modelId);
        return item != null ? item.getModelName() : this.modelRegistry.getDefaultModelName();
    }

    // ==================== 预置示例问题生成 ====================

    /**
     * 基于已注册 Tool 的描述信息，调用 LLM 生成多样化的示例问题。
     * 首次调用时生成并缓存，后续直接返回缓存结果；生成失败时降级为静态默认问题。
     */
    public List<String> generateHints() {
        ToolSnapshot snapshot = this.toolRegistryService.currentSnapshot();
        if (this.cachedHints != null && this.cachedHintsVersion == snapshot.version()) return this.cachedHints;

        try {
            // 收集当前 Tool 快照的 description 作为 LLM 的输入
            StringBuilder sb = new StringBuilder();
            for (String description : snapshot.descriptions()) {
                sb.append("- ").append(description).append("\n");
            }

            // 用不带 tools 的 ChatClient 调用 LLM 生成示例问题
            var noToolsClient = this.resolveNoToolsClient(null);

            String response = noToolsClient.prompt()
                    .advisors(advisor -> advisor
                            .param(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), Boolean.FALSE))
                    .system("你是一个只输出问题的机器，禁止输出任何解释、前言、总结或编号。直接从第一个问题开始输出。")
                    .user("参考以下 ERP 工具能力，生成 4 个简短自然的中文疑问句（每个≤20字），覆盖不同模块，每行一个。\n\n"
                            + "要求：\n"
                            + "- 使用自然疑问句式，如'...有哪些？'、'...是多少？'、'...怎么样？'\n"
                            + "- 禁止用'查询'、'查看'等祈使动词开头\n"
                            + "- 禁止包含具体公司名、产品名、订单号，使用通用表述\n\n"
                            + sb
                            + "\n示例格式：\n最近有哪些销售订单？\n库存不足的产品有哪些？\n上月质检合格率多少？\n本月收支情况如何？")
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                return getDefaultHints();
            }

            // 后处理：去编号/引号，按问号结尾过滤，限制长度
            List<String> hints = Arrays.stream(response.split("\n"))
                    .map(String::trim)
                    .map(s -> s.replaceFirst("^[\\d.、)）\\-*·]+\\s*", ""))
                    .map(s -> s.replaceAll("[\"'“”‘’]", ""))
                    .map(String::trim)
                    .filter(s -> s.length() <= 30
                            && (s.endsWith("？") || s.endsWith("?")))
                    .limit(4)
                    .toList();

            if (hints.size() >= 4) {
                this.cachedHints = hints;
                this.cachedHintsVersion = snapshot.version();
            }
            return hints.isEmpty() ? getDefaultHints() : hints;
        } catch (Exception e) {
            log.warn("生成预置问题失败: {}", e.getMessage());
            return getDefaultHints();
        }
    }

    /** LLM 生成失败时的静态降级问题（通用表述，不含具体公司/产品名） */
    private static List<String> getDefaultHints() {
        return List.of(
                "最近有哪些销售订单？",
                "库存不足的产品有哪些？",
                "上月质检合格率多少？",
                "本月收支情况如何？");
    }

    // ==================== 文档搜索（不经过 LLM，不计费） ====================

    /**
     * 纯文档搜索：只从向量库检索相似文档片段，不调用 LLM，不产生 token 消耗。
     * 可用于调试检索效果、预览将提供给 LLM 的上下文。
     */
    public List<DocSnippet> searchDocs(String query, int topK) {
        return this.vectorStore.similaritySearch(this.buildTenantSearchRequest(query, topK, 0.0)).stream()
                .map(doc -> new DocSnippet(
                        doc.getText(),
                        (String) doc.getMetadata().get("source"),
                        doc.getScore()))
                .collect(Collectors.toList());
    }

    /** 文档搜索结果片段 */
    public record DocSnippet(String text, String source, Double score) {
    }

    // ==================== 对话录制核心逻辑 ====================

    /**
     * LLM 调用前的准备工作：
     * 1. 防御层：校验会话未被软删除，若已删除直接抛 {@link IllegalStateException} 阻断后续动作
     * 2. 确保会话记录存在 + 保存用户消息（事务 A）
     * 3. 校验计费配额，不满足则抛出 {@link IllegalStateException} 阻止 LLM 调用
     * <p>
     * 异常分类处理：
     * <ul>
     *   <li>{@link IllegalStateException} —— 业务级拒绝（如续写软删除会话、配额不足等），
     *       必须向上传播，由 {@code GlobalExceptionHandler} 统一返回 {@code BIZ_ERROR}，
     *       同时阻止后续的配额检查与 LLM 调用，避免「幽灵消息」与无依据扣费</li>
     *   <li>其他持久化抖动（{@link org.springframework.dao.DataAccessException} 等） —— 仅
     *       记录 warn 后继续走 LLM 流程，与本变更前行为一致</li>
     * </ul>
     * <p>
     * 防御性校验放在 try-catch 之外：必须在写入任何用户消息之前完成，且任何业务异常
     * 都不能被下方的兜底 catch 吞掉。
     */
    private void prepareConversation(String conversationId, String question, String mode,
                                     boolean requireExistingConversation) {
        // 防御层（try-catch 之外）：拒绝乱传/已软删除会话，避免写入幽灵消息
        this.chatHistoryService.requireConversationActive(conversationId, requireExistingConversation);
        try {
            this.chatHistoryService.initConversationAndSaveUserMessage(conversationId, question, mode);
        } catch (IllegalStateException e) {
            // 业务级拒绝信号：必须传播，不能吞掉
            throw e;
        } catch (Exception e) {
            log.warn("保存用户消息失败: {}", e.getMessage());
        }
        this.billingService.checkQuota();
    }

    /**
     * 非流式响应的后处理：提取回答文本和 token 用量，执行消息保存和计费扣除。
     * <p>
     * 事务 B（消息保存）和事务 C（计费扣除）独立提交，C 失败不回滚 B。
     */
    private String recordAndReturn(String conversationId, String mode, String modelName,
                                   ChatResponse response, long durationMs, String traceId) {
        String content = this.extractContent(response);
        int[] tokens = this.extractTokenUsage(response);
        String toolCalls = this.toolCallRecorder.getToolCallsJson(traceId);
        int toolCallsCount = this.toolCallRecorder.getToolCallCount(traceId);
        // 事务 B：保存助手消息 + 更新会话统计
        try {
            String messageId = this.chatHistoryService.saveAssistantMessageAndUpdateStats(conversationId, content, mode,
                    modelName, tokens[0], tokens[1], tokens[2],
                    toolCalls, toolCallsCount, 0, (int) durationMs);
            this.attachToolCallLogs(traceId, messageId);
        } catch (Exception e) {
            log.warn("保存助手消息失败: {}", e.getMessage());
        }
        // 事务 C：计费扣除（独立事务，失败不影响消息记录）
        try {
            this.billingService.deductForTokenUsage(tokens[2], tokens[0], tokens[1],
                    modelName, conversationId);
        } catch (Exception e) {
            log.warn("计费扣除失败: {}", e.getMessage());
        }
        return content;
    }

    /**
     * 流式响应的包装：将 Flux&lt;ChatResponse&gt; 转换为 Flux&lt;String&gt;，
     * 并在流的各阶段植入录制逻辑。
     * <p>
     * 管道结构：doOnNext(捕获用量) → mapNotNull(提取文本) → doOnNext(累加) → doFinally(保存+扣费)
     */
    private Flux<String> streamWithRecording(Flux<ChatResponse> responseFlux,
                                             String conversationId, String mode, String modelName,
                                             String traceId) {
        long startTime = System.currentTimeMillis();
        StringBuilder contentBuilder = new StringBuilder();
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // 在 Servlet 线程捕获租户上下文，用于 contextWrite 注入 Reactor Context
        String entCode = TenantContext.getEntCode();
        String userId = TenantContext.getUserId();

        return responseFlux
                .doOnNext(resp -> {
                    // 捕获 token 用量（通常只有最后一个 chunk 包含完整用量）
                    Usage usage = resp.getMetadata().getUsage();
                    if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                        usageRef.set(usage);
                    }
                })
                .mapNotNull(resp -> {
                    var result = resp.getResult();
                    return result != null && result.getOutput() != null ? result.getOutput().getText() : null;
                })
                .doOnNext(contentBuilder::append)
                .doOnError(errorRef::set)
                .publishOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    // 恢复租户上下文（双保险：contextWrite 自动传播 + 手动设置）
                    TenantContext.setEntCode(entCode);
                    TenantContext.setUserId(userId);
                    try {
                        int[] tokens = this.extractTokenUsageFromRef(usageRef.get());
                        int durationMs = (int) (System.currentTimeMillis() - startTime);
                        String content = contentBuilder.toString();
                        boolean cancelled = signal == SignalType.CANCEL;
                        boolean failed = signal == SignalType.ON_ERROR;
                        String status = cancelled ? "cancelled" : failed ? "error" : "success";
                        String errorMessage = failed && errorRef.get() != null
                                ? errorRef.get().getMessage() : null;
                        String toolCalls = this.toolCallRecorder.getToolCallsJson(traceId);
                        int toolCallsCount = this.toolCallRecorder.getToolCallCount(traceId);

                        // 事务 B：保存助手消息（根据终止原因写入 success / cancelled / error）
                        try {
                            String messageId = this.chatHistoryService.saveAssistantMessageAndUpdateStats(conversationId,
                                    content, mode, modelName,
                                    tokens[0], tokens[1], tokens[2],
                                    toolCalls, toolCallsCount, 0, durationMs, status, errorMessage);
                            this.attachToolCallLogs(traceId, messageId);
                        } catch (Exception e) {
                            log.warn("流式-保存助手消息失败: {}", e.getMessage());
                        }
                        // 事务 C：仅在成功或取消时结算一次；异常流不进入扣费
                        if (!failed) {
                            try {
                                this.billingService.deductForTokenUsage(tokens[2], tokens[0], tokens[1],
                                        modelName, conversationId);
                            } catch (Exception e) {
                                log.warn("流式-计费扣除失败: {}", e.getMessage());
                            }
                        }
                    } finally {
                        this.toolCallRecorder.clear(traceId);
                        TenantContext.clear();
                    }
                })
                // 将租户信息写入 Reactor Context，自动传播到整个上游管道（含 Tool Calling 线程）
                .contextWrite(ctx -> ctx.put(
                        TenantContextAccessor.KEY,
                        new TenantContextAccessor.TenantInfo(entCode, userId)));
    }

    // ==================== 内部工具方法 ====================

    /** 从 ChatResponse 元数据中安全提取 token 用量 → [promptTokens, completionTokens, totalTokens] */
    private int[] extractTokenUsage(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        return this.extractTokenUsageFromRef(usage);
    }

    /** 从 Usage 对象中安全提取 token 用量（null 安全，避免自动拆箱 NPE） */
    private int[] extractTokenUsageFromRef(Usage usage) {
        if (usage == null) {
            return new int[]{0, 0, 0};
        }
        return new int[]{
                safeUnbox(usage.getPromptTokens()),
                safeUnbox(usage.getCompletionTokens()),
                safeUnbox(usage.getTotalTokens())
        };
    }

    /** Integer → int 安全拆箱 */
    private static int safeUnbox(Integer value) {
        return value != null ? value : 0;
    }

    /** 从 ChatResponse 中安全提取 LLM 回答文本 */
    private String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    // ==================== 向量搜索请求构建 ====================

    /** 构建带租户隔离的向量搜索请求（默认 topK=5），始终按 ent_code 过滤 */
    private SearchRequest buildTenantSearchRequest(String query) {
        return this.buildTenantSearchRequest(query, 5, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /** 构建带租户隔离的向量搜索请求（自定义 topK 和阈值） */
    private SearchRequest buildTenantSearchRequest(String query, int topK, double threshold) {
        String entCode = TenantContext.requireEntCode();
        var b = new FilterExpressionBuilder();
        Filter.Expression filter = b.eq("ent_code", entCode).build();

        return SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(filter)
                .build();
    }

    /**
     * 根据 modelId 获取不带 tools 的 ChatClient。
     * <p>
     * knowledge 模式和预置问题生成只需要 LLM 文本能力，不能向模型暴露 ERP Tool。
     */
    private ChatClient resolveNoToolsClient(String modelId) {
        var item = this.modelRegistry.getModelItem(modelId);
        String provider = item != null ? item.getProvider() : "default";
        return this.providerNoToolsClientCache.computeIfAbsent(provider, p -> {
            ChatModel chatModel = this.modelRegistry.getChatModel(modelId);
            if (chatModel == null) {
                chatModel = this.modelRegistry.getChatModel(null);
            }
            if (chatModel == null) {
                return this.chatClientBuilder.clone()
                        .defaultSystem(SYSTEM_PROMPT)
                        .build();
            }
            return ChatClient.builder(chatModel)
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
        });
    }

    /**
     * 构建传递给 ToolCallback 的链路上下文。
     *
     * @param traceId        单次问答链路 ID
     * @param conversationId 会话 ID
     * @param mode           问答模式
     * @param modelName      使用模型
     * @return Tool 上下文
     */
    private Map<String, Object> buildToolContext(String traceId, String conversationId, String mode, String modelName) {
        return Map.of(
                "traceId", traceId,
                "conversationId", conversationId,
                "mode", mode,
                "model", modelName,
                "entCode", TenantContext.requireEntCode(),
                "userId", TenantContext.getUserIdOrDefault());
    }

    /**
     * 将本次 Tool 调用流水关联到已保存的助手消息。
     *
     * @param traceId   问答链路 ID
     * @param messageId 助手消息 ID
     */
    private void attachToolCallLogs(String traceId, String messageId) {
        try {
            this.toolCallLogService.attachMessageId(traceId, messageId);
        } catch (Exception e) {
            log.warn("回填 Tool 调用流水消息 ID 失败: {}", e.getMessage());
        }
    }

    /**
     * 清理同一 provider 的旧 Tool 版本 ChatClient 缓存。
     *
     * @param provider       模型 provider
     * @param currentVersion 当前 Tool 快照版本
     */
    private void clearOlderProviderClientCache(String provider, long currentVersion) {
        String prefix = provider + ":";
        this.providerClientCache.keySet().removeIf(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            try {
                return Long.parseLong(key.substring(prefix.length())) < currentVersion;
            }
            catch (NumberFormatException ex) {
                return false;
            }
        });
    }

}
