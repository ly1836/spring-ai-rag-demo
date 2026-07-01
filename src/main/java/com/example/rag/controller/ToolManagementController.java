package com.example.rag.controller;

import java.util.List;

import com.example.rag.tool.admin.ToolManagementService;
import com.example.rag.vo.AdminVO;
import com.example.rag.vo.RespVO;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM Tool 管理 REST API 控制器。
 */
@RestController
@RequestMapping("/api/admin/tools")
public class ToolManagementController {

	/** LLM Tool 管理服务。 */
	private final ToolManagementService toolManagementService;

	/**
	 * 创建 LLM Tool 管理 REST API 控制器。
	 *
	 * @param toolManagementService LLM Tool 管理服务
	 */
	public ToolManagementController(ToolManagementService toolManagementService) {
		this.toolManagementService = toolManagementService;
	}

	/**
	 * 查询动态 Tool 定义列表。
	 *
	 * @return 动态 Tool 定义列表
	 */
	@GetMapping
	public RespVO<List<AdminVO.ToolItem>> listTools() {
		return RespVO.success(toolManagementService.listTools());
	}

	/**
	 * 新增动态 Tool 定义。
	 *
	 * @param item 动态 Tool 定义
	 * @return 是否保存成功
	 */
	@PostMapping
	public RespVO<Boolean> saveTool(@RequestBody AdminVO.ToolItem item) {
		return RespVO.success(toolManagementService.saveTool(item));
	}

	/**
	 * 更新动态 Tool 定义。
	 *
	 * @param item 动态 Tool 定义
	 * @return 是否更新成功
	 */
	@PutMapping
	public RespVO<Boolean> updateTool(@RequestBody AdminVO.ToolItem item) {
		return RespVO.success(toolManagementService.updateTool(item));
	}

	/**
	 * 删除动态 Tool 定义。
	 *
	 * @param id Tool ID
	 * @return 是否删除成功
	 */
	@DeleteMapping("/{id}")
	public RespVO<Boolean> deleteTool(@PathVariable Long id) {
		return RespVO.success(toolManagementService.deleteTool(id));
	}

	/**
	 * 手动刷新当前 Tool 快照。
	 *
	 * @return Tool 刷新结果
	 */
	@PostMapping("/refresh")
	public RespVO<AdminVO.ToolRefreshResult> refreshTools() {
		return RespVO.success(toolManagementService.refreshTools());
	}

	/**
	 * 查询当前租户的 Tool 调用流水。
	 *
	 * @param page     页码
	 * @param size     每页条数
	 * @param toolName Tool 名称
	 * @return Tool 调用流水列表
	 */
	@GetMapping("/call-logs")
	public RespVO<List<AdminVO.ToolCallLogItem>> listCallLogs(@RequestParam(defaultValue = "0") Integer page,
			@RequestParam(defaultValue = "50") Integer size,
			@RequestParam(required = false) String toolName) {
		return RespVO.success(toolManagementService.listCallLogs(page, size, toolName));
	}

}
