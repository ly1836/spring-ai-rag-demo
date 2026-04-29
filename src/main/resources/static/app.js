/**
 * ERP 智能助手 — 前端交互逻辑
 *
 * 对接后端 API（所有 JSON 响应均包装在 RespVO 中，data 字段为实际数据）：
 *   POST /api/load            — 加载预置文档
 *   POST /api/upload          — 上传文件
 *   GET  /api/ask             — AI 问答（非流式）
 *   GET  /api/ask/stream      — AI 流式问答（SSE，不包装 RespVO）
 *   GET  /api/search          — 文档搜索
 *   GET  /api/conversations   — 对话列表
 *   GET  /api/conversations/{id}/messages — 对话消息
 *   DELETE /api/conversations/{id}        — 删除对话
 *   GET  /api/billing/account      — 计费账户
 *   GET  /api/billing/plans        — 套餐列表
 *   GET  /api/billing/transactions — 交易流水
 *   POST /api/billing/recharge     — 充值
 *   GET  /api/billing/usage/daily  — 每日用量
 *   GET  /api/billing/usage/monthly— 月度用量
 */

const API = '/api';
/** 当前问答模式：auto(智能) / data(数据查询) / knowledge(知识问答) */
let currentMode = 'auto';
/** 是否正在进行流式问答（防止重复提交） */
let isStreaming = false;
/** 当前选中的上传文件 */
let selectedFile = null;
/** 当前会话 ID，同一会话中的消息共享此 ID */
let currentConversationId = null;
/** AI 生成的预置示例问题缓存 */
let loadedHints = null;
/** 当前选中的模型 ID */
let currentModelId = '';
/** 当前流式请求的 AbortController */
let currentStreamController = null;
/** 当前流式回复对应的气泡 DOM */
let currentStreamBubble = null;
/** 是否由用户主动终止当前流 */
let isUserCancellingStream = false;

// ============================================================
//  通用工具
// ============================================================

/** 获取当前租户编码：下拉选择或自定义输入 */
function getEntCode() {
  const sel = document.getElementById('entCodeSelect');
  if (sel.value === '__custom__') {
    return document.getElementById('entCodeCustom').value.trim() || 'ENT001';
  }
  return sel.value;
}

/** 获取当前用户 ID */
function getUserId() { return document.getElementById('userId').value.trim() || 'U002'; }

/** 租户选择变更：切换自定义输入框的显示 */
function onEntCodeChange() {
  const sel = document.getElementById('entCodeSelect');
  const custom = document.getElementById('entCodeCustom');
  custom.style.display = sel.value === '__custom__' ? 'inline' : 'none';
  if (sel.value === '__custom__') custom.focus();
}

/** 构造请求 Headers，附带租户和用户标识 */
function getHeaders() {
  return { 'X-Ent-Code': getEntCode(), 'X-User-Id': getUserId() };
}

/** 底部弹出提示消息，3 秒后自动消失 */
function showToast(msg, type = 'success') {
  const t = document.createElement('div');
  t.className = 'toast ' + type;
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}

/** HTML 转义，防止 XSS */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/**
 * 将文本通过 marked.js 渲染为 HTML。
 * 优先使用 marked.parse()，库未加载时降级为纯文本转义。
 * 用于助手消息的实时 Markdown 渲染（流式 + 最终）。
 */
function renderMarkdown(text) {
  if (!text) return '';
  var parseFn = (typeof marked !== 'undefined') && (marked.parse || marked);
  if (typeof parseFn === 'function') {
    try {
      return parseFn(text);
    } catch (e) { /* marked 解析失败，降级为纯文本 */ }
  }
  return escapeHtml(text);
}

/**
 * 从一个完整的 SSE 事件文本中提取 data 字段值。
 *
 * Spring WebFlux 对 Flux<String> 的 SSE 编码，在 data 内容含 \n 时
 * 不一定为每行都添加 data: 前缀。因此解析策略为：
 *   - data: 开头的行 → 提取为数据行
 *   - 非 SSE 标准字段（event:/id:/retry:/:）的行 → 视为上一个 data 的续行
 * 多行之间用 \n 拼接以还原原始换行。
 */
function parseSSEEventData(eventText) {
  var parts = [];
  var inData = false;
  var lines = eventText.split('\n');
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    if (line.startsWith('data:')) {
      if (inData) parts.push('\n');
      var v = line.substring(5);
      parts.push(v.charAt(0) === ' ' ? v.substring(1) : v);
      inData = true;
    } else if (inData && line !== '' &&
               !line.startsWith('event:') && !line.startsWith('id:') &&
               !line.startsWith('retry:') && !line.startsWith(':')) {
      parts.push('\n');
      parts.push(line);
    }
  }
  return parts.join('');
}

/**
 * 对容器内所有 <pre><code> 块应用 highlight.js 语法高亮。
 * 仅在流式结束后调用一次，避免频繁 DOM 操作。
 */
function highlightCodeBlocks(container) {
  if (typeof hljs === 'undefined') return;
  container.querySelectorAll('pre code').forEach(function(block) {
    hljs.highlightElement(block);
  });
}

/**
 * 通用 API 调用：自动附加租户/用户 Header，解析 RespVO 包装。
 * @returns {Promise<*>} RespVO.data 字段
 * @throws {Error} 当 success=false 时抛出 errMsg
 */
async function apiCall(url, options = {}) {
  const res = await fetch(url, { ...options, headers: { ...getHeaders(), ...(options.headers || {}) } });
  const json = await res.json();
  if (json.success === false) {
    throw new Error(json.errMsg || json.errCode || '请求失败');
  }
  return json.data;
}

/** POST JSON 请求的快捷方式 */
async function apiPost(url, body) {
  return apiCall(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

// ============================================================
//  Tab 导航
// ============================================================

/** 切换顶部 Tab（AI 对话 / 历史记录 / 计费管理），自动加载对应数据 */
function switchTab(tab) {
  document.querySelectorAll('.nav-btn').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.getElementById('tab' + tab.charAt(0).toUpperCase() + tab.slice(1)).classList.add('active');

  if (tab === 'history') loadConversations();
  if (tab === 'billing') { loadAccount(); loadPlans(); }
}

// ============================================================
//  文档管理 — 加载预置文档
// ============================================================

/** 一键加载 classpath:docs/ 下的预置产品手册到向量库 */
async function loadPresetDocs() {
  const btn = document.getElementById('btnLoad');
  btn.disabled = true; btn.textContent = '加载中...';
  try {
    const data = await apiCall(API + '/load', { method: 'POST' });
    showToast('已加载 ' + data.chunksLoaded + ' 个文档片段');
  } catch (e) { showToast(e.message, 'error'); }
  finally { btn.disabled = false; btn.textContent = '加载预置文档到向量库'; }
}

// ============================================================
//  文档管理 — 文件上传（拖拽 + 点击）
// ============================================================

/** 初始化上传区域的拖拽和文件选择事件 */
function initUpload() {
  const dropZone = document.getElementById('dropZone');
  const fileInput = document.getElementById('fileInput');
  dropZone.addEventListener('dragover', e => { e.preventDefault(); dropZone.classList.add('dragover'); });
  dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
  dropZone.addEventListener('drop', e => {
    e.preventDefault(); dropZone.classList.remove('dragover');
    if (e.dataTransfer.files.length) { selectedFile = e.dataTransfer.files[0]; onFileSelected(); }
  });
  fileInput.addEventListener('change', () => {
    if (fileInput.files.length) { selectedFile = fileInput.files[0]; onFileSelected(); }
  });
}

/** 文件选中后更新 UI：显示文件名、启用上传按钮 */
function onFileSelected() {
  document.getElementById('dropZone').querySelector('p').innerHTML = '&#9989; ' + selectedFile.name;
  document.getElementById('btnUpload').disabled = false;
}

/** 将选中文件上传到向量库（FormData 方式） */
async function uploadFile() {
  if (!selectedFile) return;
  const btn = document.getElementById('btnUpload');
  btn.disabled = true; btn.textContent = '上传中...';
  const form = new FormData();
  form.append('file', selectedFile);
  try {
    const data = await apiCall(API + '/upload', { method: 'POST', body: form });
    showToast(data.filename + ' 已导入 ' + data.chunksLoaded + ' 个片段');
    resetUploadArea();
  } catch (e) { showToast(e.message, 'error'); }
  finally { btn.disabled = !selectedFile; btn.textContent = '上传'; }
}

/** 重置上传区域为初始状态 */
function resetUploadArea() {
  selectedFile = null;
  document.getElementById('fileInput').value = '';
  document.getElementById('dropZone').querySelector('p').innerHTML =
    '点击或拖拽文件上传<br><span style="font-size:11px;color:var(--text-muted)">支持 PDF / Word / Excel / TXT</span>';
  document.getElementById('btnUpload').disabled = true;
}

// ============================================================
//  文档搜索（仅向量检索，不调用 LLM）
// ============================================================

/** 从向量库搜索相似文档片段，展示相似度评分 */
async function searchDocs() {
  const q = document.getElementById('searchQuery').value.trim();
  if (!q) return;
  const container = document.getElementById('searchResults');
  container.innerHTML = '<p class="placeholder-text">搜索中...</p>';
  try {
    const data = await apiCall(API + '/search?' + new URLSearchParams({ query: q, topK: 5 }));
    if (!data.results || !data.results.length) {
      container.innerHTML = '<p class="placeholder-text">未找到相关文档</p>'; return;
    }
    container.innerHTML = data.results.map(r =>
      '<div class="search-result-card">' +
        '<div>' + escapeHtml(r.text).substring(0, 300) + (r.text.length > 300 ? '...' : '') + '</div>' +
        '<div class="meta">' +
          '<span>&#128196; ' + (r.source || '-') + '</span>' +
          '<span class="score">&#9733; ' + (r.score != null ? (r.score * 100).toFixed(1) + '%' : '-') + '</span>' +
        '</div>' +
      '</div>'
    ).join('');
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">搜索失败: ' + e.message + '</p>'; }
}

// ============================================================
//  聊天 — 模式切换 & 消息气泡
// ============================================================

/** 切换问答模式（智能/数据查询/知识问答） */
function setMode(btn) {
  document.querySelectorAll('#modeGroup button').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  currentMode = btn.dataset.mode;
}

/**
 * 在聊天面板中添加一条消息气泡。
 * - 用户消息：纯文本显示
 * - 助手消息：添加 markdown-body class，支持 Markdown 渲染
 * @returns {HTMLElement} 气泡 DOM 元素（用于流式更新 innerHTML）
 */
function addMessage(role, text, mode) {
  const welcome = document.getElementById('welcome');
  if (welcome) welcome.remove();
  const container = document.getElementById('chatMessages');
  const msgDiv = document.createElement('div');
  msgDiv.className = 'msg ' + role;
  const avatar = document.createElement('div');
  avatar.className = 'msg-avatar';
  avatar.innerHTML = role === 'user' ? '&#128100;' : '&#129302;';
  const wrapper = document.createElement('div');
  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble' + (role === 'assistant' ? ' markdown-body' : '');
  if (role === 'assistant' && text) {
    bubble.innerHTML = renderMarkdown(text);
  } else {
    bubble.textContent = text;
  }
  wrapper.appendChild(bubble);
  var meta = document.createElement('div');
  meta.className = 'msg-meta';
  if (mode && role === 'user') {
    var ml = document.createElement('span');
    ml.className = 'msg-mode';
    ml.textContent = ({ auto: '智能模式', data: '数据查询', knowledge: '知识问答' })[mode] || mode;
    meta.appendChild(ml);
  }
  var copyBtn = document.createElement('button');
  copyBtn.className = 'msg-copy-btn';
  copyBtn.innerHTML = '&#128203; 复制';
  copyBtn.onclick = function() {
    var rawText = bubble.innerText || bubble.textContent;
    navigator.clipboard.writeText(rawText).then(function() {
      copyBtn.innerHTML = '&#9989; 已复制';
      setTimeout(function() { copyBtn.innerHTML = '&#128203; 复制'; }, 1500);
    });
  };
  meta.appendChild(copyBtn);
  wrapper.appendChild(meta);
  msgDiv.appendChild(avatar);
  msgDiv.appendChild(wrapper);
  container.appendChild(msgDiv);
  container.scrollTop = container.scrollHeight;
  return bubble;
}

/** 添加"正在输入"动画指示器 */
function addTypingIndicator() {
  const welcome = document.getElementById('welcome'); if (welcome) welcome.remove();
  const container = document.getElementById('chatMessages');
  const msgDiv = document.createElement('div'); msgDiv.className = 'msg assistant'; msgDiv.id = 'typingMsg';
  const avatar = document.createElement('div'); avatar.className = 'msg-avatar'; avatar.innerHTML = '&#129302;';
  const bubble = document.createElement('div'); bubble.className = 'msg-bubble typing-indicator';
  bubble.innerHTML = '<span></span><span></span><span></span>';
  msgDiv.appendChild(avatar); msgDiv.appendChild(bubble);
  container.appendChild(msgDiv); container.scrollTop = container.scrollHeight;
}

/** 移除"正在输入"动画指示器 */
function removeTypingIndicator() { const el = document.getElementById('typingMsg'); if (el) el.remove(); }

/** 切换流式问答中的按钮和输入框视觉状态 */
function setStreamingState(streaming) {
  isStreaming = streaming;
  var sendBtn = document.getElementById('sendBtn');
  var stopBtn = document.getElementById('stopBtn');
  var input = document.getElementById('questionInput');
  sendBtn.disabled = streaming;
  stopBtn.hidden = !streaming;
  stopBtn.disabled = !streaming;
  stopBtn.textContent = '停止';
  input.classList.toggle('streaming', streaming);
}

/** 将助手气泡渲染为 Markdown，并可选追加“已终止”提示 */
function renderAssistantBubble(bubble, text, cancelled) {
  if (!bubble) return;
  bubble.dataset.rawText = text || '';
  if (!text) {
    bubble.innerHTML = '<p>（未收到回复）</p>';
  } else {
    bubble.innerHTML = renderMarkdown(text);
  }
  if (cancelled) {
    bubble.innerHTML += '<div class="stream-stop-note">回答已终止</div>';
  }
}

/** 用户主动终止当前流式回答 */
function stopStreaming() {
  if (!isStreaming || !currentStreamController) return;
  isUserCancellingStream = true;
  var stopBtn = document.getElementById('stopBtn');
  stopBtn.disabled = true;
  stopBtn.textContent = '停止中...';
  currentStreamController.abort();
}

// ============================================================
//  聊天 — 发送问题（SSE 流式响应 + 实时 Markdown 渲染）
// ============================================================

/** 点击示例问题自动填入并发送 */
function askHint(el) { document.getElementById('questionInput').value = el.textContent; sendQuestion(); }

/** 回车发送（Shift+Enter 换行） */
function handleInputKey(e) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendQuestion(); } }

/**
 * 发送问题并以 SSE 流式接收回答。
 *
 * 流程：
 * 1. 构造请求参数，建立 SSE 连接（GET /api/ask/stream）
 * 2. 逐 chunk 读取 ReadableStream，解析 SSE data: 行并拼接
 * 3. 每次收到新 chunk 后，用 marked.parse() 实时渲染 Markdown 到气泡
 * 4. 流结束后做最终渲染 + 代码块语法高亮
 */
async function sendQuestion() {
  const input = document.getElementById('questionInput');
  const question = input.value.trim();
  if (!question || isStreaming) return;

  const mode = currentMode;
  // 首次提问时自动生成会话 ID
  if (!currentConversationId) {
    currentConversationId = crypto.randomUUID();
    updateConversationTag();
  }

  input.value = '';
  addMessage('user', question, mode);
  addTypingIndicator();
  setStreamingState(true);
  isUserCancellingStream = false;
  currentStreamBubble = null;
  currentStreamController = new AbortController();

  try {
    const params = new URLSearchParams({ question, mode, conversationId: currentConversationId, modelId: currentModelId });
    // 建立 SSE 连接
    const res = await fetch(API + '/ask/stream?' + params, {
      headers: { ...getHeaders(), 'Accept': 'text/event-stream' },
      signal: currentStreamController.signal
    });
    if (!res.ok) { const t = await res.text(); throw new Error(res.status + ': ' + t); }

    removeTypingIndicator();
    const bubble = addMessage('assistant', '', mode);
    currentStreamBubble = bubble;
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let fullText = '';
    let sseBuffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        const remaining = decoder.decode();
        if (remaining) sseBuffer += remaining;
        break;
      }
      sseBuffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n').replace(/\r/g, '\n');

      // 只处理已完整接收的 SSE 事件（以 \n\n 分隔）
      let idx;
      while ((idx = sseBuffer.indexOf('\n\n')) !== -1) {
        var eventText = sseBuffer.substring(0, idx);
        sseBuffer = sseBuffer.substring(idx + 2);
        fullText += parseSSEEventData(eventText);
      }

      renderAssistantBubble(bubble, fullText, false);
      document.getElementById('chatMessages').scrollTop = document.getElementById('chatMessages').scrollHeight;
    }

    // 处理缓冲区中可能残留的最后一个事件（服务端关闭时可能无尾部 \n\n）
    if (sseBuffer.trim()) {
      fullText += parseSSEEventData(sseBuffer);
    }

    renderAssistantBubble(bubble, fullText, false);
    if (fullText) {
      highlightCodeBlocks(bubble);
    }
    document.getElementById('chatMessages').scrollTop = document.getElementById('chatMessages').scrollHeight;
  } catch (e) {
    removeTypingIndicator();
    if (e.name === 'AbortError' && isUserCancellingStream) {
      if (!currentStreamBubble) {
        currentStreamBubble = addMessage('assistant', '', mode);
      }
      renderAssistantBubble(currentStreamBubble, currentStreamBubble.dataset.rawText || '', true);
    } else {
      addMessage('assistant', '请求失败: ' + e.message);
    }
  } finally {
    currentStreamController = null;
    currentStreamBubble = null;
    isUserCancellingStream = false;
    setStreamingState(false);
    input.focus();
  }
}

/** 更新底部会话标签显示 */
function updateConversationTag() {
  const tag = document.getElementById('conversationTag');
  if (currentConversationId) {
    tag.style.display = 'inline';
    tag.textContent = '会话: ' + currentConversationId.substring(0, 8) + '...';
    tag.title = currentConversationId;
  } else { tag.style.display = 'none'; }
}

/** 构建预置问题 HTML（基于缓存的 loadedHints 或显示加载中状态） */
function buildHintsHtml() {
  if (loadedHints && loadedHints.length) {
    return '<div class="hints" id="hintContainer">' +
      loadedHints.map(function(h) {
        return '<div class="hint" onclick="askHint(this)">' + escapeHtml(h) + '</div>';
      }).join('') + '</div>';
  }
  return '<div class="hints" id="hintContainer">' +
    '<div class="hint" style="opacity:.5;cursor:default">正在生成推荐问题…</div></div>';
}

/** 从后端获取 AI 生成的预置示例问题，更新欢迎页和缓存 */
async function loadHints() {
  try {
    var data = await apiCall(API + '/hints');
    if (data.hints && data.hints.length) {
      loadedHints = data.hints;
    }
  } catch (e) {
    loadedHints = [
      '最近有哪些销售订单？',
      '库存不足的产品有哪些？',
      '上月质检合格率多少？',
      '本月收支情况如何？'
    ];
  }
  var container = document.getElementById('hintContainer');
  if (container && loadedHints) {
    container.innerHTML = loadedHints.map(function(h) {
      return '<div class="hint" onclick="askHint(this)">' + escapeHtml(h) + '</div>';
    }).join('');
  }
}

/** 重置聊天面板，开始新对话 */
function newConversation() {
  currentConversationId = null;
  updateConversationTag();
  var container = document.getElementById('chatMessages');
  container.innerHTML = '';
  var welcome = document.createElement('div'); welcome.className = 'welcome'; welcome.id = 'welcome';
  welcome.innerHTML = '<div class="icon">&#x1f916;</div><h2>ERP 智能助手</h2>' +
    '<p>我可以帮你查询 ERP 业务数据，也可以回答产品知识问题。试试问我：</p>' +
    buildHintsHtml();
  container.appendChild(welcome);
}

// ============================================================
//  历史记录
// ============================================================

/** 加载对话列表（分页，默认 50 条） */
async function loadConversations() {
  const container = document.getElementById('historyList');
  container.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const data = await apiCall(API + '/conversations?' + new URLSearchParams({ page: 0, size: 50 }));
    if (!data.data || !data.data.length) {
      container.innerHTML = '<p class="placeholder-text">暂无对话记录</p>'; return;
    }
    container.innerHTML = data.data.map(c =>
      '<div class="history-item" onclick="loadMessages(\'' + c.conversationId + '\')">' +
        '<div class="history-item-title">' + escapeHtml(c.title || '无标题') + '</div>' +
        '<div class="history-item-meta">' +
          '<span>' + (c.mode || '-') + '</span>' +
          '<span>' + (c.messageCount || 0) + ' 条消息</span>' +
          '<span>' + (c.totalTokens || 0) + ' tokens</span>' +
          '<span>' + formatTime(c.updatedAt || c.createdAt) + '</span>' +
        '</div>' +
        '<button class="btn-icon" title="删除" onclick="event.stopPropagation();deleteConversation(\'' + c.conversationId + '\')">&times;</button>' +
      '</div>'
    ).join('');
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

/** 加载指定会话的消息列表，助手消息使用 Markdown 渲染 */
async function loadMessages(conversationId) {
  document.querySelectorAll('.history-item').forEach(el => el.classList.remove('selected'));
  event.currentTarget?.classList?.add('selected');
  const container = document.getElementById('historyMessages');
  document.getElementById('historyDetailTitle').textContent = '会话 ' + conversationId.substring(0, 8) + '...';
  container.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const data = await apiCall(API + '/conversations/' + conversationId + '/messages');
    if (!data.messages || !data.messages.length) {
      container.innerHTML = '<p class="placeholder-text">暂无消息</p>'; return;
    }
    container.innerHTML = data.messages.map(m =>
      '<div class="history-msg ' + (m.role || 'user') + '">' +
        '<div class="history-msg-header">' +
          '<span class="role-badge ' + m.role + '">' + (m.role === 'user' ? '用户' : '助手') + '</span>' +
          '<span>' + formatTime(m.createdAt) + '</span>' +
          (m.status && m.status !== 'success'
            ? '<span class="message-status-badge ' + escapeHtml(m.status) + '">' +
                escapeHtml(({ cancelled: '已终止', error: '失败' })[m.status] || m.status) +
              '</span>'
            : '') +
          (m.totalTokens ? '<span>' + m.totalTokens + ' tokens</span>' : '') +
          (m.durationMs ? '<span>' + m.durationMs + 'ms</span>' : '') +
        '</div>' +
        '<div class="history-msg-content' + (m.role === 'assistant' ? ' markdown-body' : '') + '">' +
          (m.role === 'assistant' ? renderMarkdown(m.content || '') : escapeHtml(m.content || '')) +
        '</div>' +
      '</div>'
    ).join('');
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

/** 删除指定会话（需确认） */
async function deleteConversation(conversationId) {
  if (!confirm('确认删除该对话？')) return;
  try {
    await apiCall(API + '/conversations/' + conversationId, { method: 'DELETE' });
    showToast('已删除');
    loadConversations();
    document.getElementById('historyMessages').innerHTML = '<p class="placeholder-text">&#8592; 从左侧选择一个对话</p>';
  } catch (e) { showToast(e.message, 'error'); }
}

/** 格式化时间戳为 MM/DD HH:mm */
function formatTime(ts) {
  if (!ts) return '-';
  try { return new Date(ts).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }); }
  catch { return ts; }
}

// ============================================================
//  计费 — 账户概览
// ============================================================

/** 加载当前租户的计费账户信息（套餐、余额、用量、状态） */
async function loadAccount() {
  const card = document.getElementById('billingAccountCard');
  card.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const d = await apiCall(API + '/billing/account');
    card.innerHTML =
      '<div class="account-grid">' +
        '<div class="account-stat"><div class="label">套餐</div><div class="value">' + (d.planName || '-') + '</div></div>' +
        '<div class="account-stat primary"><div class="label">余额</div><div class="value">&yen; ' + (d.balance != null ? Number(d.balance).toFixed(2) : '-') + '</div></div>' +
        '<div class="account-stat"><div class="label">本月用量</div><div class="value">' + fmtNum(d.usedTokensThisMonth) + ' / ' + (d.monthlyTokenQuota > 0 ? fmtNum(d.monthlyTokenQuota) : '不限') + '</div></div>' +
        '<div class="account-stat"><div class="label">累计充值</div><div class="value">&yen; ' + (d.totalRecharged != null ? Number(d.totalRecharged).toFixed(2) : '-') + '</div></div>' +
        '<div class="account-stat"><div class="label">累计消费</div><div class="value">&yen; ' + (d.totalConsumed != null ? Number(d.totalConsumed).toFixed(2) : '-') + '</div></div>' +
        '<div class="account-stat"><div class="label">状态</div><div class="value badge-' + d.status + '">' + d.status + '</div></div>' +
      '</div>';
  } catch (e) { card.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

/** 数字格式化（千分位） */
function fmtNum(n) { return n != null ? Number(n).toLocaleString() : '-'; }

// ============================================================
//  计费 — 套餐列表
// ============================================================

/** 加载可用套餐卡片 */
async function loadPlans() {
  const container = document.getElementById('billingPlans');
  container.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const data = await apiCall(API + '/billing/plans');
    if (!data.plans || !data.plans.length) { container.innerHTML = '<p class="placeholder-text">暂无套餐</p>'; return; }
    container.innerHTML = '<div class="plans-grid">' + data.plans.map(p =>
      '<div class="plan-card">' +
        '<div class="plan-name">' + (p.planName || p.planCode) + '</div>' +
        '<div class="plan-price">&yen; ' + (p.monthlyPrice || 0) + '<span>/月</span></div>' +
        '<ul>' +
          '<li>Token 配额: ' + (p.monthlyTokenQuota > 0 ? fmtNum(p.monthlyTokenQuota) : '不限') + '</li>' +
          '<li>日会话上限: ' + (p.maxConversationsPerDay || '不限') + '</li>' +
          '<li>单次上限: ' + (p.maxTokensPerRequest ? fmtNum(p.maxTokensPerRequest) : '不限') + ' tokens</li>' +
          '<li>用户数: ' + (p.maxUsers || '不限') + '</li>' +
        '</ul>' +
      '</div>'
    ).join('') + '</div>';
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

// ============================================================
//  计费 — 交易流水
// ============================================================

/** 加载交易流水表格（充值、扣费、赠送等） */
async function loadTransactions() {
  const container = document.getElementById('billingTransactions');
  container.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const data = await apiCall(API + '/billing/transactions?' + new URLSearchParams({ page: 0, size: 50 }));
    if (!data.data || !data.data.length) { container.innerHTML = '<p class="placeholder-text">暂无交易记录</p>'; return; }
    container.innerHTML =
      '<table class="data-table"><thead><tr>' +
        '<th>时间</th><th>类型</th><th>金额</th><th>余额</th><th>Token</th><th>描述</th><th>操作人</th>' +
      '</tr></thead><tbody>' +
      data.data.map(t =>
        '<tr>' +
          '<td>' + formatTime(t.createdAt) + '</td>' +
          '<td><span class="type-badge ' + t.type + '">' + t.type + '</span></td>' +
          '<td class="' + (Number(t.amount) >= 0 ? 'text-success' : 'text-error') + '">' + Number(t.amount).toFixed(4) + '</td>' +
          '<td>' + (t.balanceAfter != null ? Number(t.balanceAfter).toFixed(2) : '-') + '</td>' +
          '<td>' + (t.tokenCount || '-') + '</td>' +
          '<td>' + (t.description || '-') + '</td>' +
          '<td>' + (t.operator || '-') + '</td>' +
        '</tr>'
      ).join('') + '</tbody></table>';
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

// ============================================================
//  计费 — 每日 / 月度用量统计
// ============================================================

/** 加载当月每日用量明细 */
async function loadDailyUsage() {
  const container = document.getElementById('billingUsageDaily');
  const today = new Date().toISOString().substring(0, 10);
  const monthStart = today.substring(0, 8) + '01';
  container.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const data = await apiCall(API + '/billing/usage/daily?' + new URLSearchParams({ startDate: monthStart, endDate: today }));
    if (!data.data || !data.data.length) { container.innerHTML = '<p class="placeholder-text">暂无数据</p>'; return; }
    container.innerHTML =
      '<table class="data-table"><thead><tr>' +
        '<th>日期</th><th>用户</th><th>模型</th><th>请求数</th><th>输入Token</th><th>输出Token</th><th>总Token</th><th>费用</th>' +
      '</tr></thead><tbody>' +
      data.data.map(r =>
        '<tr><td>' + r.usageDate + '</td><td>' + r.userId + '</td><td>' + r.model + '</td>' +
        '<td>' + r.requestCount + '</td><td>' + fmtNum(r.totalPromptTokens) + '</td>' +
        '<td>' + fmtNum(r.totalCompletionTokens) + '</td><td>' + fmtNum(r.totalTokens) + '</td>' +
        '<td>' + Number(r.estimatedCost).toFixed(4) + '</td></tr>'
      ).join('') + '</tbody></table>';
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

/** 加载月度用量汇总 */
async function loadMonthlyUsage() {
  const container = document.getElementById('billingUsageMonthly');
  container.innerHTML = '<p class="placeholder-text">加载中...</p>';
  try {
    const data = await apiCall(API + '/billing/usage/monthly');
    if (!data.data || !data.data.length) { container.innerHTML = '<p class="placeholder-text">暂无数据</p>'; return; }
    container.innerHTML =
      '<table class="data-table"><thead><tr>' +
        '<th>月份</th><th>模型</th><th>请求数</th><th>输入Token</th><th>输出Token</th><th>总Token</th><th>活跃用户</th><th>费用</th>' +
      '</tr></thead><tbody>' +
      data.data.map(r =>
        '<tr><td>' + r.usageMonth + '</td><td>' + r.model + '</td><td>' + r.requestCount + '</td>' +
        '<td>' + fmtNum(r.totalPromptTokens) + '</td><td>' + fmtNum(r.totalCompletionTokens) + '</td>' +
        '<td>' + fmtNum(r.totalTokens) + '</td><td>' + r.activeUsers + '</td>' +
        '<td>' + Number(r.estimatedCost).toFixed(4) + '</td></tr>'
      ).join('') + '</tbody></table>';
  } catch (e) { container.innerHTML = '<p style="color:var(--error);">' + e.message + '</p>'; }
}

// ============================================================
//  计费 — 充值
// ============================================================

/** 执行充值操作 */
async function doRecharge() {
  const amount = parseFloat(document.getElementById('rechargeAmount').value);
  const operator = document.getElementById('rechargeOperator').value.trim() || getUserId();
  if (!amount || amount <= 0) { showToast('请输入有效金额', 'error'); return; }
  try {
    const data = await apiPost(API + '/billing/recharge', { amount, operator });
    showToast('充值成功！余额: ¥' + Number(data.balanceAfter).toFixed(2));
    document.getElementById('rechargeAmount').value = '';
    loadAccount();
  } catch (e) { showToast(e.message, 'error'); }
}

// ============================================================
//  计费 — 子 Tab 切换
// ============================================================

/** 切换计费管理的子标签（套餐/交易/每日/月度/充值），自动加载数据 */
function switchBillingTab(btn, panelId) {
  document.querySelectorAll('.billing-tab').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  document.querySelectorAll('.billing-content').forEach(p => p.style.display = 'none');
  document.getElementById(panelId).style.display = 'block';

  if (panelId === 'billingPlans') loadPlans();
  if (panelId === 'billingTransactions') loadTransactions();
  if (panelId === 'billingUsageDaily') loadDailyUsage();
  if (panelId === 'billingUsageMonthly') loadMonthlyUsage();
}

// ============================================================
//  初始化
// ============================================================

/** 从后端 /api/models 加载可用模型列表，填充下拉框并设置默认选中 */
async function loadModels() {
  try {
    var data = await apiCall(API + '/models');
    var select = document.getElementById('modelSelect');
    if (!data.models || !data.models.length) return;
    select.innerHTML = data.models.map(function(m) {
      return '<option value="' + escapeHtml(m.id) + '"' + (m.isDefault ? ' selected' : '') + '>' +
        escapeHtml(m.label) + '</option>';
    }).join('');
    var defaultModel = data.models.find(function(m) { return m.isDefault; }) || data.models[0];
    currentModelId = defaultModel ? defaultModel.id : '';
    select.onchange = function() { currentModelId = select.value; };
  } catch (e) {
    document.getElementById('modelSelect').innerHTML = '<option value="">默认模型</option>';
  }
}

document.addEventListener('DOMContentLoaded', () => {
  initUpload();
  loadModels();
  loadHints();
});
