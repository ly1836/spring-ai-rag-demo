package com.example.rag.controller;

import com.example.rag.conversation.ChatHistoryService;
import com.example.rag.vo.ConversationVO;
import com.example.rag.vo.RespVO;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话记录 REST API 控制器。
 * <p>
 * 提供以下接口：
 * <ul>
 *   <li>GET    /api/conversations                       — 查询当前用户的会话列表（分页）</li>
 *   <li>GET    /api/conversations/{id}/messages          — 查询指定会话的消息列表</li>
 *   <li>DELETE /api/conversations/{id}                   — 删除（归档）指定会话</li>
 * </ul>
 * 所有接口自动按 ent_code + user_id 进行数据隔离。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

	private final ChatHistoryService chatHistoryService;

	public ConversationController(ChatHistoryService chatHistoryService) {
		this.chatHistoryService = chatHistoryService;
	}

	/** 查询当前用户的会话列表（按最后更新时间倒序，支持分页） */
	@GetMapping
	public RespVO<ConversationVO.ListConversationsResponse> listConversations(
			ConversationVO.ListConversationsRequest request) {
		return RespVO.success(new ConversationVO.ListConversationsResponse(
			request.page(), request.size(), chatHistoryService.getConversations(request.page(), request.size())));
	}

	/** 查询指定会话的所有消息（按时间正序） */
	@GetMapping("/{conversationId}/messages")
	public RespVO<ConversationVO.ConversationMessagesResponse> getMessages(@PathVariable String conversationId) {
		return RespVO.success(new ConversationVO.ConversationMessagesResponse(
			conversationId, chatHistoryService.getMessages(conversationId)));
	}

	/** 删除会话（软删除，将状态设为 deleted，不物理删除数据） */
	@DeleteMapping("/{conversationId}")
	public RespVO<ConversationVO.DeleteConversationResponse> deleteConversation(@PathVariable String conversationId) {
		chatHistoryService.archiveConversation(conversationId);
		return RespVO.success(new ConversationVO.DeleteConversationResponse(conversationId));
	}

}
