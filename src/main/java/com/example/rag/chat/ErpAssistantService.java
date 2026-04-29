package com.example.rag.chat;

import com.example.rag.billing.BillingService;
import com.example.rag.config.TenantContext;
import com.example.rag.config.TenantContextAccessor;
import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.tool.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.Usage;
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

import org.springframework.ai.tool.ToolCallback;

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

    /** 默认 provider（DeepSeek）的 ChatClient，已装配 system prompt + tools */
    private final ChatClient baseChatClient;

    /** PgVector 向量数据库，存储产品手册的向量嵌入 */
    private final VectorStore vectorStore;

    /** 对话记录服务，负责会话和消息的持久化 */
    private final ChatHistoryService chatHistoryService;

    /** 计费服务，负责配额校验、token 扣费和流水记录 */
    private final BillingService billingService;

    /** 所有 Tool Bean 实例（用于反射读取 @Tool 描述 + 构建非默认 provider 的 ChatClient） */
    private final List<Object> toolBeans;

    /** 会话记忆：基于 a_chat_message 表的 JDBC 存储，内存零占用，重启不丢失 */
    private final ChatMemory chatMemory;

    /** 非默认 provider → 带 system prompt + tools 的 ChatClient 缓存（懒加载） */
    private final Map<String, ChatClient> providerClientCache = new ConcurrentHashMap<>();

    /** AI 生成的预置示例问题缓存，首次请求后缓存 */
    private volatile List<String> cachedHints;

    /**
     * 构造函数：注入所有依赖，构建默认 ChatClient。
     * <p>
     * chatClientBuilder 由 Spring AI 自动配置注入，基于 @Primary 的 DeepSeek ChatModel。
     * 通过 defaultSystem() 和 defaultTools() 装配系统提示词和 8 大模块 Tool。
     */
    /**
     * @param tools Spring 自动注入所有 BaseTool 子类（@Component），新增 Tool 模块无需修改此处
     */
    public ErpAssistantService(ChatClient.Builder chatClientBuilder,
                               ModelRegistry modelRegistry,
                               VectorStore vectorStore,
                               ChatHistoryService chatHistoryService,
                               BillingService billingService,
                               ChatMemoryRepository chatMemoryRepository,
                               List<BaseTool> tools) {
        this.toolBeans = List.copyOf(tools);
        // 构建默认 provider 的 ChatClient，装配系统提示词和所有 Tool
        this.baseChatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(this.toolBeans.toArray())
                .build();
        this.modelRegistry = modelRegistry;
        this.vectorStore = vectorStore;
        this.chatHistoryService = chatHistoryService;
        this.billingService = billingService;
        // 基于 JDBC 的会话记忆，maxMessages 由 JdbcChatMemoryRepository 的 SQL LIMIT 控制
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    // ==================== 自动模式（Tool Calling + RAG） ====================

    /** 自动模式问答（非流式）：LLM 同时拥有 Tool Calling 和 RAG 能力，自行判断调用哪个 */
    public String ask(String question, String conversationId, String modelId) {
        String mode = "auto";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode);

        long startTime = System.currentTimeMillis();
        // 挂载会话记忆 + RAG 检索两个 Advisor
        ChatResponse response = this.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName).build())
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(this.buildTenantSearchRequest(question))
                                .build())
                .user(question)
                .call()
                .chatResponse();

        return this.recordAndReturn(conversationId, mode, modelName, response, System.currentTimeMillis() - startTime);
    }

    /** 自动模式问答（流式 SSE）：功能同 ask()，以逐 chunk 方式返回 */
    public Flux<String> askStream(String question, String conversationId, String modelId) {
        String mode = "auto";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode);

        return this.streamWithRecording(
                this.resolveClient(modelId).prompt()
                        .options(ChatOptions.builder().model(modelName).build())
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build(),
                                QuestionAnswerAdvisor.builder(vectorStore)
                                        .searchRequest(this.buildTenantSearchRequest(question))
                                        .build())
                        .user(question)
                        .stream().chatResponse(),
                conversationId, mode, modelName);
    }

    // ==================== 纯数据模式（仅 Tool Calling） ====================

    /** 数据模式问答（非流式）：仅通过 Tool Calling 查询 ERP 数据库，不检索产品手册 */
    public String askData(String question, String conversationId, String modelId) {
        String mode = "data";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode);

        long startTime = System.currentTimeMillis();
        // 仅挂载会话记忆 Advisor，不挂载 RAG Advisor
        ChatResponse response = this.resolveClient(modelId).prompt()
                .options(ChatOptions.builder().model(modelName).build())
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build())
                .user(question)
                .call()
                .chatResponse();

        return this.recordAndReturn(conversationId, mode, modelName, response, System.currentTimeMillis() - startTime);
    }

    /** 数据模式问答（流式 SSE） */
    public Flux<String> askDataStream(String question, String conversationId, String modelId) {
        String mode = "data";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode);

        return this.streamWithRecording(
                this.resolveClient(modelId).prompt()
                        .options(ChatOptions.builder().model(modelName).build())
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build())
                        .user(question)
                        .stream().chatResponse(),
                conversationId, mode, modelName);
    }

    // ==================== 纯知识模式（仅 RAG） ====================

    /** 知识模式问答（非流式）：仅检索产品手册，禁用 Tool Calling */
    public String askKnowledge(String question, String conversationId, String modelId) {
        String mode = "knowledge";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode);

        // 通过 mutate() 创建不带 tools 的 ChatClient 副本，禁止 LLM 调用工具
        var noToolsClient = this.resolveClient(modelId).mutate()
                .defaultToolCallbacks(new ToolCallback[0])
                .build();

        long startTime = System.currentTimeMillis();
        ChatResponse response = noToolsClient.prompt()
                .options(ChatOptions.builder().model(modelName).build())
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(this.buildTenantSearchRequest(question))
                                .build())
                .user(question)
                .call()
                .chatResponse();

        return this.recordAndReturn(conversationId, mode, modelName, response, System.currentTimeMillis() - startTime);
    }

    /** 知识模式问答（流式 SSE） */
    public Flux<String> askKnowledgeStream(String question, String conversationId, String modelId) {
        String mode = "knowledge";
        String modelName = this.resolveModelName(modelId);
        this.prepareConversation(conversationId, question, mode);

        var noToolsClient = this.resolveClient(modelId).mutate()
                .defaultToolCallbacks(new ToolCallback[0])
                .build();

        return this.streamWithRecording(
                noToolsClient.prompt()
                        .options(ChatOptions.builder().model(modelName).build())
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).conversationId(conversationId).build(),
                                QuestionAnswerAdvisor.builder(vectorStore)
                                        .searchRequest(this.buildTenantSearchRequest(question))
                                        .build())
                        .user(question)
                        .stream().chatResponse(),
                conversationId, mode, modelName);
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
        var item = this.modelRegistry.getModelItem(modelId);
        if (item == null) return this.baseChatClient;

        String provider = item.getProvider();

        // 默认 provider 直接用 baseChatClient（已在构造函数中构建）
        var defaultItem = this.modelRegistry.getModelItem(null);
        if (defaultItem != null && provider.equals(defaultItem.getProvider())) {
            return this.baseChatClient;
        }

        // 非默认 provider：从 ModelRegistry 获取对应 ChatModel，构建新的 ChatClient
        return this.providerClientCache.computeIfAbsent(provider, p -> {
            ChatModel chatModel = this.modelRegistry.getChatModel(modelId);
            if (chatModel == null) return this.baseChatClient;
            // 用 provider 的 ChatModel + 相同的系统提示词和工具构建完整 ChatClient
            return ChatClient.builder(chatModel)
                    .defaultSystem(SYSTEM_PROMPT)
                    .defaultTools(this.toolBeans.toArray())
                    .build();
        });
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
        if (this.cachedHints != null) return this.cachedHints;

        try {
            // 收集所有 @Tool 方法的 description 作为 LLM 的输入
            StringBuilder sb = new StringBuilder();
            for (Object tool : this.toolBeans) {
                for (var method : tool.getClass().getDeclaredMethods()) {
                    var ann = method.getAnnotation(
                            org.springframework.ai.tool.annotation.Tool.class);
                    if (ann != null && !ann.description().isEmpty()) {
                        sb.append("- ").append(ann.description()).append("\n");
                    }
                }
            }

            // 用不带 tools 的 ChatClient 调用 LLM 生成示例问题
            var noToolsClient = this.baseChatClient.mutate()
                    .defaultToolCallbacks(new ToolCallback[0])
                    .build();

            String response = noToolsClient.prompt()
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
     * 1. 确保会话记录存在 + 保存用户消息（事务 A）
     * 2. 校验计费配额，不满足则抛出 IllegalStateException 阻止 LLM 调用
     */
    private void prepareConversation(String conversationId, String question, String mode) {
        try {
            this.chatHistoryService.initConversationAndSaveUserMessage(conversationId, question, mode);
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
                                   ChatResponse response, long durationMs) {
        String content = this.extractContent(response);
        int[] tokens = this.extractTokenUsage(response);
        // 事务 B：保存助手消息 + 更新会话统计
        try {
            this.chatHistoryService.saveAssistantMessageAndUpdateStats(conversationId, content, mode,
                    modelName, tokens[0], tokens[1], tokens[2],
                    null, 0, 0, (int) durationMs);
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
                                             String conversationId, String mode, String modelName) {
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

                        // 事务 B：保存助手消息（根据终止原因写入 success / cancelled / error）
                        try {
                            this.chatHistoryService.saveAssistantMessageAndUpdateStats(conversationId,
                                    content, mode, modelName,
                                    tokens[0], tokens[1], tokens[2],
                                    null, 0, 0, durationMs, status, errorMessage);
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

}
