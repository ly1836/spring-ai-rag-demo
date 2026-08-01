package com.example.rag.controller;

import com.example.rag.chat.DocumentLoaderService;
import com.example.rag.chat.ErpAssistantService;
import com.example.rag.chat.dto.ChatAnswerResult;
import com.example.rag.chat.dto.ChatStreamFrame;
import com.example.rag.chat.dto.DocSnippet;
import com.example.rag.config.ModelProperties;
import com.example.rag.vo.ChatVO;
import com.example.rag.vo.RespVO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ERP 智能助手 REST API 控制器。
 * <p>
 * 提供以下接口：
 * <ul>
 *   <li>POST /api/load         — 加载预置文档到向量库</li>
 *   <li>POST /api/upload       — 上传文件（PDF/Word/TXT）到向量库</li>
 *   <li>GET  /api/ask          — AI 问答（非流式，自动记录对话和计费）</li>
 *   <li>GET  /api/ask/stream   — AI 问答（SSE 流式返回，不包装 RespVO）</li>
 *   <li>GET  /api/search       — 搜索相似文档片段（不调用 LLM，不计费）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ErpAssistantService assistantService;
    private final DocumentLoaderService documentLoaderService;
    private final ModelProperties modelProperties;

    public ChatController(ErpAssistantService assistantService,
                          DocumentLoaderService documentLoaderService,
                          ModelProperties modelProperties) {
        this.assistantService = assistantService;
        this.documentLoaderService = documentLoaderService;
        this.modelProperties = modelProperties;
    }

    // ==================== 文档管理 ====================

    /**
     * 一键加载 classpath:docs/ 目录下的预置文档到向量库（系统初始化用）
     */
    @PostMapping("/load")
    public RespVO<ChatVO.LoadDocumentsResponse> loadDocuments() {
        int count = documentLoaderService.loadFromClasspath();
        return RespVO.success(new ChatVO.LoadDocumentsResponse(count));
    }

    /**
     * 上传文件到向量库。
     */
    @PostMapping("/upload")
    public RespVO<ChatVO.UploadFileResponse> uploadFile(
            @RequestParam("file") MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename == null) filename = "unknown.txt";

        int count = documentLoaderService.loadFile(file.getInputStream(), filename);
        return RespVO.success(new ChatVO.UploadFileResponse(filename, count));
    }

    // ==================== AI 问答 ====================

    /**
     * 统一问答入口（非流式）。根据 mode 参数选择回答策略：
     * auto — Tool Calling + RAG 同时启用；data — 仅查数据库；knowledge — 仅检索产品手册。
     * 自动创建或复用会话，记录对话历史和 token 用量，执行计费扣除。
     */
    @GetMapping("/ask")
    public RespVO<ChatVO.AskResponse> ask(ChatVO.AskRequest request) {
        boolean hasConversationId = !request.conversationId().isBlank();
        String cid = hasConversationId ? request.conversationId() : UUID.randomUUID().toString();
        String mid = request.modelId();

        ChatAnswerResult result = switch (request.mode()) {
            case "data" -> assistantService.askData(request.question(), cid, mid, hasConversationId);
            case "knowledge" -> assistantService.askKnowledge(request.question(), cid, mid, hasConversationId);
            default -> assistantService.ask(request.question(), cid, mid, hasConversationId);
        };

        return RespVO.success(new ChatVO.AskResponse(
                cid, request.question(), result.answer(), request.mode(), result.chart()));
    }

    @GetMapping(value = "/ask/stream", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> askStream(ChatVO.AskRequest request) {
        boolean hasConversationId = !request.conversationId().isBlank();
        String cid = hasConversationId ? request.conversationId() : UUID.randomUUID().toString();
        String mid = request.modelId();
        try {
            // 前后端一体部署直接使用类型化 SSE，每个 data 都是可独立解析的 JSON。
            Flux<ChatStreamFrame> frames = switch (request.mode()) {
                case "data" -> assistantService.askDataStream(request.question(), cid, mid, hasConversationId);
                case "knowledge" -> assistantService.askKnowledgeStream(request.question(), cid, mid, hasConversationId);
                default -> assistantService.askStream(request.question(), cid, mid, hasConversationId);
            };
            Flux<ServerSentEvent<Object>> body = frames.map(frame -> ServerSentEvent.builder(frame.data())
                    .event(frame.event())
                    .build());
            return ResponseEntity.ok()
                    .header("X-Conversation-Id", cid)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(RespVO.error("BIZ_ERROR", e.getMessage(), e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(RespVO.error("PARAM_ERROR", e.getMessage(), e));
        }
    }

    // ==================== 可用模型列表 ====================

    /**
     * 返回 YAML 中配置的可用模型列表，供前端下拉框展示
     */
    @GetMapping("/models")
    public RespVO<ChatVO.ModelsResponse> models() {
        List<ChatVO.ModelItem> list = modelProperties.getModels().stream()
                .map(m -> new ChatVO.ModelItem(m.getId(), m.getLabel(), m.getModelName(), m.isDefault()))
                .collect(Collectors.toList());
        return RespVO.success(new ChatVO.ModelsResponse(list));
    }

    // ==================== 预置示例问题 ====================

    /**
     * AI 根据已注册 Tool 描述生成多样化的示例问题（首次调用后缓存）
     */
    @GetMapping("/hints")
    public RespVO<ChatVO.HintsResponse> hints() {
        return RespVO.success(new ChatVO.HintsResponse(assistantService.generateHints()));
    }

    // ==================== 文档搜索 ====================

    /**
     * 从向量库中搜索与关键词最相似的文档片段（不调用 LLM，不计费）。
     * 可用于调试 RAG 检索效果，预览将提供给 LLM 的上下文。
     */
    @GetMapping("/search")
    public RespVO<ChatVO.DocSearchResponse> search(ChatVO.DocSearchRequest request) {
        List<DocSnippet> raw = assistantService.searchDocs(
                request.query(), request.topK());

        List<ChatVO.DocSnippetResponse> results = raw.stream()
                .map(d -> new ChatVO.DocSnippetResponse(d.text(), d.source(), d.score()))
                .collect(Collectors.toList());

        return RespVO.success(new ChatVO.DocSearchResponse(request.query(), results));
    }

}
