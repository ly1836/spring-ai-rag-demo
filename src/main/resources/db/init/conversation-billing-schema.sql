-- ============================================================
-- 对话记录 / Token 用量追踪 / 计费系统 — DDL & DML
-- 适配多租户 ERP 智能助手，所有表均包含 ent_code 租户隔离字段
-- 数据库: MySQL
-- ============================================================

-- ============================================================
-- 一、DDL —— 建表语句
-- ============================================================

-- ==========================================================
-- 1. 租户 & 用户
-- ==========================================================

-- 租户表（企业信息）
CREATE TABLE IF NOT EXISTS a_tenant (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ent_code    VARCHAR(32)   NOT NULL COMMENT '租户编码（全局唯一）',
    ent_name    VARCHAR(100)  NOT NULL COMMENT '企业名称',
    contact     VARCHAR(50)            COMMENT '联系人',
    phone       VARCHAR(20)            COMMENT '联系电话',
    status      VARCHAR(10)   NOT NULL DEFAULT 'active' COMMENT '状态: active/suspended/closed',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ent_code (ent_code)
) COMMENT '租户表';

-- 租户用户表
CREATE TABLE IF NOT EXISTS a_tenant_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(32)   NOT NULL COMMENT '用户ID（租户内唯一）',
    ent_code     VARCHAR(32)   NOT NULL COMMENT '所属租户编码',
    username     VARCHAR(50)   NOT NULL COMMENT '登录账号',
    display_name VARCHAR(50)            COMMENT '显示名称',
    role         VARCHAR(20)   NOT NULL DEFAULT 'user' COMMENT '角色: admin/user/viewer',
    status       VARCHAR(10)   NOT NULL DEFAULT 'active' COMMENT '状态: active/disabled',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ent_user (ent_code, user_id),
    INDEX idx_ent_code (ent_code)
) COMMENT '租户用户表';

-- ==========================================================
-- 2. 对话记录
-- ==========================================================

-- 对话会话表（一个会话包含多轮对话）
CREATE TABLE IF NOT EXISTS a_chat_conversation (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id  VARCHAR(36)   NOT NULL COMMENT '会话ID（UUID）',
    ent_code         VARCHAR(32)   NOT NULL COMMENT '租户编码',
    user_id          VARCHAR(32)   NOT NULL COMMENT '用户ID',
    title            VARCHAR(200)           COMMENT '会话标题（取首条消息摘要）',
    mode             VARCHAR(20)   NOT NULL DEFAULT 'auto' COMMENT '问答模式: auto/data/knowledge',
    message_count    INT           NOT NULL DEFAULT 0 COMMENT '消息总数',
    total_tokens     INT           NOT NULL DEFAULT 0 COMMENT '会话累计 token 数',
    status           VARCHAR(10)   NOT NULL DEFAULT 'active' COMMENT '状态: active/archived/deleted',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversation_id (conversation_id),
    INDEX idx_ent_user (ent_code, user_id),
    INDEX idx_created_at (created_at)
) COMMENT '对话会话表';

-- 对话消息表（每一轮问答的详细记录）
CREATE TABLE IF NOT EXISTS a_chat_message (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id          VARCHAR(36)   NOT NULL COMMENT '消息ID（UUID）',
    conversation_id     VARCHAR(36)   NOT NULL COMMENT '所属会话ID',
    ent_code            VARCHAR(32)   NOT NULL COMMENT '租户编码',
    user_id             VARCHAR(32)   NOT NULL COMMENT '用户ID',
    role                VARCHAR(15)   NOT NULL COMMENT '角色: user/assistant/system',
    content             TEXT                   COMMENT '消息内容',
    mode                VARCHAR(20)            COMMENT '问答模式: auto/data/knowledge',
    model               VARCHAR(50)            COMMENT '使用的模型名称（如 deepseek-chat）',
    prompt_tokens       INT           NOT NULL DEFAULT 0 COMMENT '提示词 token 数',
    completion_tokens   INT           NOT NULL DEFAULT 0 COMMENT '生成回答 token 数',
    total_tokens        INT           NOT NULL DEFAULT 0 COMMENT '总 token 数',
    tool_calls          TEXT                   COMMENT '工具调用记录（JSON 数组）',
    tool_calls_count    INT           NOT NULL DEFAULT 0 COMMENT '工具调用次数',
    chart_spec          TEXT                   COMMENT '助手图表数据（ChartSpec JSON，最大60KiB）',
    rag_doc_count       INT           NOT NULL DEFAULT 0 COMMENT 'RAG 检索文档数',
    duration_ms         INT                    COMMENT '响应耗时（毫秒）',
    status              VARCHAR(10)   NOT NULL DEFAULT 'success' COMMENT '状态: success/error/timeout',
    error_message       VARCHAR(500)           COMMENT '错误信息（失败时记录）',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_ent_user (ent_code, user_id),
    INDEX idx_created_at (created_at)
) COMMENT '对话消息表';

-- ==========================================================
-- 3. Token 用量统计
-- ==========================================================

-- 每日 token 用量汇总表（按租户+用户+模型维度聚合，便于统计和账单生成）
CREATE TABLE IF NOT EXISTS a_token_usage_daily (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    ent_code                VARCHAR(32)  NOT NULL COMMENT '租户编码',
    user_id                 VARCHAR(32)  NOT NULL COMMENT '用户ID',
    usage_date              DATE         NOT NULL COMMENT '统计日期',
    model                   VARCHAR(50)  NOT NULL COMMENT '模型名称',
    request_count           INT          NOT NULL DEFAULT 0 COMMENT '请求次数',
    total_prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '累计提示词 token',
    total_completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '累计生成 token',
    total_tokens            INT          NOT NULL DEFAULT 0 COMMENT '累计总 token',
    total_tool_calls        INT          NOT NULL DEFAULT 0 COMMENT '累计工具调用次数',
    estimated_cost          DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '估算费用（元）',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_daily (ent_code, user_id, usage_date, model),
    INDEX idx_ent_date (ent_code, usage_date)
) COMMENT '每日 token 用量汇总表';

-- 每月 token 用量汇总表（按租户维度聚合，用于月度账单）
CREATE TABLE IF NOT EXISTS a_token_usage_monthly (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    ent_code                VARCHAR(32)  NOT NULL COMMENT '租户编码',
    usage_month             VARCHAR(7)   NOT NULL COMMENT '统计月份（yyyy-MM）',
    model                   VARCHAR(50)  NOT NULL COMMENT '模型名称',
    request_count           INT          NOT NULL DEFAULT 0 COMMENT '请求次数',
    total_prompt_tokens     BIGINT       NOT NULL DEFAULT 0 COMMENT '累计提示词 token',
    total_completion_tokens BIGINT       NOT NULL DEFAULT 0 COMMENT '累计生成 token',
    total_tokens            BIGINT       NOT NULL DEFAULT 0 COMMENT '累计总 token',
    active_users            INT          NOT NULL DEFAULT 0 COMMENT '活跃用户数',
    estimated_cost          DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT '估算费用（元）',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_monthly (ent_code, usage_month, model),
    INDEX idx_usage_month (usage_month)
) COMMENT '每月 token 用量汇总表';

-- ==========================================================
-- 4. 计费系统
-- ==========================================================

-- 计费套餐表
CREATE TABLE IF NOT EXISTS a_billing_plan (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code                VARCHAR(32)    NOT NULL COMMENT '套餐编码',
    plan_name                VARCHAR(50)    NOT NULL COMMENT '套餐名称',
    plan_type                VARCHAR(20)    NOT NULL COMMENT '套餐类型: free/basic/pro/enterprise',
    monthly_token_quota      BIGINT         NOT NULL DEFAULT 0 COMMENT '每月 token 配额（0=不限）',
    monthly_price            DECIMAL(10,2)  NOT NULL DEFAULT 0 COMMENT '月费（元）',
    overage_price_per_1k     DECIMAL(10,4)  NOT NULL DEFAULT 0 COMMENT '超额部分每千 token 单价（元）',
    max_conversations_per_day INT                    COMMENT '每日最大会话数（NULL=不限）',
    max_tokens_per_request   INT                     COMMENT '单次请求最大 token（NULL=不限）',
    max_users                INT                     COMMENT '最大用户数（NULL=不限）',
    features                 TEXT                    COMMENT '套餐功能描述（JSON）',
    status                   VARCHAR(10)    NOT NULL DEFAULT 'active' COMMENT '状态: active/discontinued',
    created_at               DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_code (plan_code)
) COMMENT '计费套餐表';

-- 模型计价规则表（不同模型的 token 单价，支持按日期生效）
CREATE TABLE IF NOT EXISTS a_billing_price_rule (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    model               VARCHAR(50)    NOT NULL COMMENT '模型名称（如 deepseek-chat）',
    input_price_per_1k  DECIMAL(10,6)  NOT NULL COMMENT '输入 token 单价（元/千token）',
    output_price_per_1k DECIMAL(10,6)  NOT NULL COMMENT '输出 token 单价（元/千token）',
    effective_date      DATE           NOT NULL COMMENT '生效日期',
    expired_date        DATE                    COMMENT '失效日期（NULL=永久有效）',
    remark              VARCHAR(200)            COMMENT '备注',
    created_at          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_model (model),
    INDEX idx_effective_date (effective_date)
) COMMENT '模型计价规则表';

-- 租户计费账户表
CREATE TABLE IF NOT EXISTS a_billing_account (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    ent_code               VARCHAR(32)    NOT NULL COMMENT '租户编码',
    plan_code              VARCHAR(32)    NOT NULL COMMENT '当前套餐编码',
    balance                DECIMAL(12,2)  NOT NULL DEFAULT 0 COMMENT '账户余额（元）',
    total_recharged        DECIMAL(12,2)  NOT NULL DEFAULT 0 COMMENT '累计充值金额',
    total_consumed         DECIMAL(12,2)  NOT NULL DEFAULT 0 COMMENT '累计消费金额',
    used_tokens_this_month BIGINT         NOT NULL DEFAULT 0 COMMENT '本月已用 token 数',
    billing_cycle_start    DATE           NOT NULL COMMENT '当前计费周期起始日',
    status                 VARCHAR(10)    NOT NULL DEFAULT 'active' COMMENT '状态: active/suspended/arrears',
    created_at             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ent_code (ent_code)
) COMMENT '租户计费账户表';

-- 计费流水表（充值、扣费、退款等所有资金变动记录）
CREATE TABLE IF NOT EXISTS a_billing_transaction (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_no      VARCHAR(36)    NOT NULL COMMENT '交易流水号（UUID）',
    ent_code            VARCHAR(32)    NOT NULL COMMENT '租户编码',
    type                VARCHAR(20)    NOT NULL COMMENT '类型: recharge/deduction/refund/monthly_fee/gift',
    amount              DECIMAL(12,4)  NOT NULL COMMENT '交易金额（正=入账，负=扣除）',
    balance_after       DECIMAL(12,2)  NOT NULL COMMENT '交易后余额',
    token_count         INT                     COMMENT '关联 token 数（扣费时记录）',
    model               VARCHAR(50)             COMMENT '关联模型（扣费时记录）',
    conversation_id     VARCHAR(36)             COMMENT '关联会话ID（扣费时记录）',
    description         VARCHAR(200)            COMMENT '交易描述',
    operator            VARCHAR(50)             COMMENT '操作人',
    created_at          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_transaction_no (transaction_no),
    INDEX idx_ent_code (ent_code),
    INDEX idx_type (type),
    INDEX idx_created_at (created_at)
) COMMENT '计费流水表';

-- 月度账单表（每月生成的正式账单）
CREATE TABLE IF NOT EXISTS a_billing_invoice (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_no          VARCHAR(32)    NOT NULL COMMENT '账单编号',
    ent_code            VARCHAR(32)    NOT NULL COMMENT '租户编码',
    billing_month       VARCHAR(7)     NOT NULL COMMENT '账单月份（yyyy-MM）',
    plan_code           VARCHAR(32)    NOT NULL COMMENT '套餐编码',
    plan_fee            DECIMAL(10,2)  NOT NULL DEFAULT 0 COMMENT '套餐月费',
    token_usage_fee     DECIMAL(10,2)  NOT NULL DEFAULT 0 COMMENT 'token 用量费用（超额部分）',
    total_amount        DECIMAL(12,2)  NOT NULL DEFAULT 0 COMMENT '账单总金额',
    total_tokens        BIGINT         NOT NULL DEFAULT 0 COMMENT '月度总 token 用量',
    total_requests      INT            NOT NULL DEFAULT 0 COMMENT '月度总请求数',
    status              VARCHAR(10)    NOT NULL DEFAULT 'pending' COMMENT '状态: pending/paid/overdue',
    paid_at             DATETIME                COMMENT '支付时间',
    created_at          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_invoice_no (invoice_no),
    UNIQUE KEY uk_ent_month (ent_code, billing_month),
    INDEX idx_billing_month (billing_month)
) COMMENT '月度账单表';

-- ==========================================================
-- 5. LLM Tool 管理与调用链路
-- ==========================================================

-- LLM 动态 Tool 定义表（全局配置，不按租户隔离）
CREATE TABLE IF NOT EXISTS a_llm_tool (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_name     VARCHAR(64)  NOT NULL COMMENT 'Tool 名称',
    tool_desc     VARCHAR(500) NOT NULL COMMENT 'Tool 描述',
    input_schema  TEXT         NOT NULL COMMENT 'Tool 入参 JSON Schema',
    sql_template  TEXT         NOT NULL COMMENT '只读 SQL 模板',
    table_alias   VARCHAR(32)           COMMENT '主表别名，用于拼接租户条件',
    result_limit  INT          NOT NULL DEFAULT 50 COMMENT '最大返回行数',
    status        VARCHAR(10)  NOT NULL DEFAULT 'active' COMMENT '状态: active/inactive',
    remark        VARCHAR(500)          COMMENT '备注',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tool_name (tool_name),
    INDEX idx_status (status)
) COMMENT 'LLM 动态 Tool 定义表';

-- LLM Tool 命中流水表（按租户隔离）
CREATE TABLE IF NOT EXISTS a_tool_call_log (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id       VARCHAR(36)  NOT NULL COMMENT '单次问答链路 ID',
    conversation_id VARCHAR(36)          COMMENT '会话 ID',
    message_id     VARCHAR(36)           COMMENT '助手消息 ID',
    ent_code       VARCHAR(32)  NOT NULL COMMENT '租户编码',
    user_id        VARCHAR(32)           COMMENT '用户 ID',
    mode           VARCHAR(20)           COMMENT '问答模式',
    model          VARCHAR(50)           COMMENT '使用模型',
    tool_name      VARCHAR(64)  NOT NULL COMMENT 'Tool 名称',
    tool_type      VARCHAR(20)  NOT NULL COMMENT 'Tool 来源: code/database',
    arguments_json TEXT                  COMMENT 'Tool 入参 JSON',
    result_count   INT          NOT NULL DEFAULT 0 COMMENT '返回结果条数',
    duration_ms    BIGINT                COMMENT '调用耗时（毫秒）',
    status         VARCHAR(10)  NOT NULL DEFAULT 'success' COMMENT '状态: success/error',
    error_message  VARCHAR(500)          COMMENT '错误信息',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id (trace_id),
    INDEX idx_ent_conversation (ent_code, conversation_id),
    INDEX idx_tool_name (tool_name),
    INDEX idx_created_at (created_at),
    INDEX idx_ent_created_at (ent_code, created_at)
) COMMENT 'LLM Tool 命中流水表';


-- ============================================================
-- 二、DML —— 模拟数据
-- ============================================================

-- ==========================================================
-- 1. 租户 & 用户
-- ==========================================================

INSERT INTO a_tenant (ent_code, ent_name, contact, phone, status) VALUES
('ENT001', '示范制造有限公司',   '陈总', '13800000001', 'active'),
('ENT002', '精密科技股份有限公司', '刘总', '13800000002', 'active'),
('ENT003', '试用企业（免费版）',   '王总', '13800000003', 'active');

INSERT INTO a_tenant_user (user_id, ent_code, username, display_name, role, status) VALUES
('U001', 'ENT001', 'admin',    '系统管理员', 'admin', 'active'),
('U002', 'ENT001', 'zhangsan', '张三',       'user',  'active'),
('U003', 'ENT001', 'lisi',     '李四',       'user',  'active'),
('U004', 'ENT001', 'wangwu',   '王五',       'viewer','active'),
('U010', 'ENT002', 'admin',    '管理员',     'admin', 'active'),
('U011', 'ENT002', 'liuyi',    '刘一',       'user',  'active'),
('U020', 'ENT003', 'admin',    '管理员',     'admin', 'active');

-- ==========================================================
-- 2. 对话记录
-- ==========================================================

INSERT INTO a_chat_conversation (conversation_id, ent_code, user_id, title, mode, message_count, total_tokens, status, created_at) VALUES
('c0a80101-0001-4000-8000-000000000001', 'ENT001', 'U002', '查询客户张三的订单情况',       'auto',      4, 2350, 'active', '2026-03-25 09:15:00'),
('c0a80101-0001-4000-8000-000000000002', 'ENT001', 'U002', '本月销售汇总统计',             'data',      2, 1280, 'active', '2026-03-25 10:30:00'),
('c0a80101-0001-4000-8000-000000000003', 'ENT001', 'U003', '智能控制器A型的维护保养说明',   'knowledge', 2, 1560, 'active', '2026-03-26 14:00:00'),
('c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', '采购单PO20260301到货和质检情况', 'auto',      6, 4820, 'active', '2026-03-27 08:45:00'),
('c0a80101-0001-4000-8000-000000000005', 'ENT001', 'U003', '库存预警及电源模块库存',        'data',      4, 2100, 'active', '2026-03-28 11:20:00'),
('c0a80101-0001-4000-8000-000000000006', 'ENT002', 'U010', '测试对话',                      'auto',      2, 980,  'active', '2026-03-28 15:00:00');

INSERT INTO a_chat_message (message_id, conversation_id, ent_code, user_id, role, content, mode, model, prompt_tokens, completion_tokens, total_tokens, tool_calls, tool_calls_count, rag_doc_count, duration_ms, status, created_at) VALUES
-- 会话1: 查询客户张三的订单
('m0001-0001', 'c0a80101-0001-4000-8000-000000000001', 'ENT001', 'U002', 'user',      '客户张三的订单有哪些？', 'auto', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-25 09:15:00'),
('m0001-0002', 'c0a80101-0001-4000-8000-000000000001', 'ENT001', 'U002', 'assistant', '根据查询结果，客户"张三电子科技有限公司"共有2笔销售订单：\n1. SO20260301 - 智能控制器A型 100台，金额50,000元，已发货\n2. SO20260302 - 传感器模组B型 200个，金额30,000元，生产中', 'auto', 'deepseek-chat', 580, 320, 900, '[{"tool":"getSalesOrders","args":{"customerName":"张三"}}]', 1, 2, 2350, 'success', '2026-03-25 09:15:03'),
('m0001-0003', 'c0a80101-0001-4000-8000-000000000001', 'ENT001', 'U002', 'user',      'SO20260301的发货状态呢？', 'auto', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-25 09:16:00'),
('m0001-0004', 'c0a80101-0001-4000-8000-000000000001', 'ENT001', 'U002', 'assistant', '订单SO20260301已通过顺丰速运发出，物流单号SF1234567890，发货日期2026-03-10，100台全部发出，状态为"已签收"。', 'auto', 'deepseek-chat', 720, 230, 950, '[{"tool":"getShipmentStatus","args":{"orderNo":"SO20260301"}}]', 1, 0, 1820, 'success', '2026-03-25 09:16:02'),

-- 会话2: 销售统计
('m0002-0001', 'c0a80101-0001-4000-8000-000000000002', 'ENT001', 'U002', 'user',      '查一下本月的销售汇总', 'data', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-25 10:30:00'),
('m0002-0002', 'c0a80101-0001-4000-8000-000000000002', 'ENT001', 'U002', 'assistant', '2026年3月销售汇总：\n- 订单数：8笔\n- 总金额：326,500元\n- 客户数：4个', 'data', 'deepseek-chat', 650, 180, 830, '[{"tool":"getSalesSummary","args":{"startDate":"2026-03-01","endDate":"2026-03-31"}}]', 1, 0, 1580, 'success', '2026-03-25 10:30:02'),

-- 会话3: 知识问答
('m0003-0001', 'c0a80101-0001-4000-8000-000000000003', 'ENT001', 'U003', 'user',      '智能控制器A型如何日常维护保养？', 'knowledge', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-26 14:00:00'),
('m0003-0002', 'c0a80101-0001-4000-8000-000000000003', 'ENT001', 'U003', 'assistant', '根据产品手册，智能控制器A型的日常维护保养包括：\n1. 每周清洁散热风扇和通风口\n2. 每月检查端子接线是否松动\n3. 每季度校验传感器精度\n4. 保持工作环境温度在-10~55℃\n5. 避免在高湿度环境下使用', 'knowledge', 'deepseek-chat', 820, 380, 1200, NULL, 0, 3, 2800, 'success', '2026-03-26 14:00:04'),

-- 会话4: 采购到货+质检（多轮）
('m0004-0001', 'c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', 'user',      '采购单PO20260301到货了吗？', 'auto', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-27 08:45:00'),
('m0004-0002', 'c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', 'assistant', 'PO20260301（华芯微电子）已全部到货，包含2条收货记录：\n1. MCU芯片STM32 - 500个，已验收\n2. 电容0805-100nF - 5000个，已验收\n收货日期均为2026-03-08。', 'auto', 'deepseek-chat', 680, 280, 960, '[{"tool":"getPurchaseReceiveStatus","args":{"poNo":"PO20260301"}}]', 1, 0, 2100, 'success', '2026-03-27 08:45:03'),
('m0004-0003', 'c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', 'user',      '这批MCU芯片的质检结果怎么样？', 'auto', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-27 08:46:00'),
('m0004-0004', 'c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', 'assistant', '批次L20260307的MCU芯片STM32来料检验结果为"合格"：\n- 抽检50个，合格50个，不良0个\n- 检验员：刘工\n- 检验日期：2026-03-08\n该批物料质量良好，无不良。', 'auto', 'deepseek-chat', 850, 310, 1160, '[{"tool":"getQualityInspection","args":{"lotNo":"L20260307"}}]', 1, 0, 2400, 'success', '2026-03-27 08:46:03'),
('m0004-0005', 'c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', 'user',      '那这笔采购的付款情况呢？', 'auto', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-27 08:47:00'),
('m0004-0006', 'c0a80101-0001-4000-8000-000000000004', 'ENT001', 'U002', 'assistant', '华芯微电子的应付账款情况：\n- PINV20260301：应付80,000元，已全额支付\n- PINV20260302：应付25,000元，已付10,000元，余额15,000元\n总应付105,000元，已付90,000元，待付余额15,000元。', 'auto', 'deepseek-chat', 920, 350, 1270, '[{"tool":"getAccountsPayable","args":{"supplierName":"华芯微电子"}}]', 1, 0, 2600, 'success', '2026-03-27 08:47:03'),

-- 会话5: 库存查询
('m0005-0001', 'c0a80101-0001-4000-8000-000000000005', 'ENT001', 'U003', 'user',      '目前有哪些库存预警的产品？', 'data', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-28 11:20:00'),
('m0005-0002', 'c0a80101-0001-4000-8000-000000000005', 'ENT001', 'U003', 'assistant', '当前低于安全库存的产品有3项：\n1. 电源模块C型（成品仓）- 可用50个，安全库存150个，缺口100个\n2. 铝合金外壳（原料仓）- 可用20个，安全库存80个，缺口60个\n3. 电机驱动板D型（成品仓）- 可用30台，安全库存50台，缺口20台\n建议尽快安排采购或生产补货。', 'data', 'deepseek-chat', 560, 340, 900, '[{"tool":"getLowStockAlerts","args":{}}]', 1, 0, 1900, 'success', '2026-03-28 11:20:03'),
('m0005-0003', 'c0a80101-0001-4000-8000-000000000005', 'ENT001', 'U003', 'user',      '电源模块C型具体库存分布呢？', 'data', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-28 11:21:00'),
('m0005-0004', 'c0a80101-0001-4000-8000-000000000005', 'ENT001', 'U003', 'assistant', '电源模块C型（P003）当前库存：\n- 成品仓：可用50个，预留20个，在途100个\n- 批次号L20260303，库位A-03-01\n在途100个到货后可缓解库存紧张。', 'data', 'deepseek-chat', 620, 250, 870, '[{"tool":"getInventory","args":{"product":"电源模块C型"}}]', 1, 0, 1650, 'success', '2026-03-28 11:21:02'),

-- ENT002 的测试对话
('m0006-0001', 'c0a80101-0001-4000-8000-000000000006', 'ENT002', 'U010', 'user',      '你好，你能做什么？', 'auto', NULL, 0, 0, 0, NULL, 0, 0, NULL, 'success', '2026-03-28 15:00:00'),
('m0006-0002', 'c0a80101-0001-4000-8000-000000000006', 'ENT002', 'U010', 'assistant', '您好！我是您的ERP智能助手，可以帮您查询销售、采购、生产、仓库、质检、委外、售后、财务等模块的业务数据，也可以回答产品知识问题。请问有什么可以帮您？', 'auto', 'deepseek-chat', 420, 180, 600, NULL, 0, 0, 1200, 'success', '2026-03-28 15:00:02');

-- ==========================================================
-- 3. Token 用量统计
-- ==========================================================

INSERT INTO a_token_usage_daily (ent_code, user_id, usage_date, model, request_count, total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost) VALUES
('ENT001', 'U002', '2026-03-25', 'deepseek-chat', 3, 1950, 730,  2680,  3, 0.0054),
('ENT001', 'U002', '2026-03-27', 'deepseek-chat', 3, 2450, 940,  3390,  3, 0.0068),
('ENT001', 'U003', '2026-03-26', 'deepseek-chat', 1, 820,  380,  1200,  0, 0.0024),
('ENT001', 'U003', '2026-03-28', 'deepseek-chat', 2, 1180, 590,  1770,  2, 0.0035),
('ENT002', 'U010', '2026-03-28', 'deepseek-chat', 1, 420,  180,  600,   0, 0.0012);

INSERT INTO a_token_usage_monthly (ent_code, usage_month, model, request_count, total_prompt_tokens, total_completion_tokens, total_tokens, active_users, estimated_cost) VALUES
('ENT001', '2026-03', 'deepseek-chat', 9,  6400,  2640,  9040,  2, 0.0181),
('ENT002', '2026-03', 'deepseek-chat', 1,  420,   180,   600,   1, 0.0012);

-- ==========================================================
-- 4. 计费系统
-- ==========================================================

-- 套餐
INSERT INTO a_billing_plan (plan_code, plan_name, plan_type, monthly_token_quota, monthly_price, overage_price_per_1k, max_conversations_per_day, max_tokens_per_request, max_users, features, status) VALUES
('FREE',       '免费体验版', 'free',       100000,       0,      0,       10,   4000,  2,    '{"rag":true,"tool_calling":true,"stream":false}',  'active'),
('BASIC',      '基础版',     'basic',      2000000,      99.00,  0.0020,  100,  8000,  10,   '{"rag":true,"tool_calling":true,"stream":true}',   'active'),
('PRO',        '专业版',     'pro',        10000000,     399.00, 0.0015,  NULL, 16000, 50,   '{"rag":true,"tool_calling":true,"stream":true,"priority_queue":true}', 'active'),
('ENTERPRISE', '企业版',     'enterprise', 0,            0,      0.0010,  NULL, NULL,  NULL, '{"rag":true,"tool_calling":true,"stream":true,"priority_queue":true,"dedicated_support":true}', 'active');

-- 模型计价规则
INSERT INTO a_billing_price_rule (model, input_price_per_1k, output_price_per_1k, effective_date, expired_date, remark) VALUES
('deepseek-chat',     0.001000, 0.002000, '2026-01-01', NULL, 'DeepSeek Chat 标准定价'),
('deepseek-reasoner', 0.004000, 0.016000, '2026-01-01', NULL, 'DeepSeek Reasoner 标准定价'),
('qwen-plus',         0.000800, 0.002000, '2026-01-01', NULL, '通义千问 Plus 标准定价'),
('glm-4-flash',       0.000000, 0.000000, '2026-01-01', NULL, '智谱 GLM-4-Flash 免费');

-- 租户计费账户
INSERT INTO a_billing_account (ent_code, plan_code, balance, total_recharged, total_consumed, used_tokens_this_month, billing_cycle_start, status) VALUES
('ENT001', 'PRO',   860.50,  1000.00, 139.50, 9040,   '2026-03-01', 'active'),
('ENT002', 'BASIC', 99.00,   99.00,   0.00,   600,    '2026-03-01', 'active'),
('ENT003', 'FREE',  0.00,    0.00,    0.00,   0,      '2026-03-15', 'active');

-- 计费流水
INSERT INTO a_billing_transaction (transaction_no, ent_code, type, amount, balance_after, token_count, model, conversation_id, description, operator, created_at) VALUES
-- ENT001
('tx-0001', 'ENT001', 'recharge',    1000.0000, 1000.00, NULL,  NULL,             NULL, '首次充值',                     '陈总',   '2026-03-01 09:00:00'),
('tx-0002', 'ENT001', 'monthly_fee', -399.0000, 601.00,  NULL,  NULL,             NULL, '3月专业版月费',                 'system', '2026-03-01 00:00:00'),
('tx-0003', 'ENT001', 'gift',        300.0000,  901.00,  NULL,  NULL,             NULL, '新客赠送300元',                 'system', '2026-03-01 09:00:00'),
('tx-0004', 'ENT001', 'deduction',   -0.0018,   900.9982, 900,  'deepseek-chat', 'c0a80101-0001-4000-8000-000000000001', '对话扣费-查询客户订单', 'system', '2026-03-25 09:16:03'),
('tx-0005', 'ENT001', 'deduction',   -0.0017,   900.9965, 830,  'deepseek-chat', 'c0a80101-0001-4000-8000-000000000002', '对话扣费-销售汇总',     'system', '2026-03-25 10:30:02'),
('tx-0006', 'ENT001', 'deduction',   -0.0024,   900.9941, 1200, 'deepseek-chat', 'c0a80101-0001-4000-8000-000000000003', '对话扣费-产品知识问答', 'system', '2026-03-26 14:00:04'),
('tx-0007', 'ENT001', 'deduction',   -0.0039,   900.9902, 3390, 'deepseek-chat', 'c0a80101-0001-4000-8000-000000000004', '对话扣费-采购质检查询', 'system', '2026-03-27 08:47:03'),
('tx-0008', 'ENT001', 'deduction',   -0.0035,   900.9867, 1770, 'deepseek-chat', 'c0a80101-0001-4000-8000-000000000005', '对话扣费-库存预警查询', 'system', '2026-03-28 11:21:02'),
-- ENT002
('tx-0101', 'ENT002', 'recharge',    99.0000,   99.00,   NULL,  NULL,             NULL, '基础版首月充值', '刘总', '2026-03-01 10:00:00'),
('tx-0102', 'ENT002', 'monthly_fee', -99.0000,  0.00,    NULL,  NULL,             NULL, '3月基础版月费',  'system', '2026-03-01 00:00:00'),
('tx-0103', 'ENT002', 'gift',        99.0000,   99.00,   NULL,  NULL,             NULL, '新客赠送99元',   'system', '2026-03-01 10:00:00'),
('tx-0104', 'ENT002', 'deduction',   -0.0012,   98.9988, 600,   'deepseek-chat', 'c0a80101-0001-4000-8000-000000000006', '对话扣费-测试对话', 'system', '2026-03-28 15:00:02');

-- 月度账单
INSERT INTO a_billing_invoice (invoice_no, ent_code, billing_month, plan_code, plan_fee, token_usage_fee, total_amount, total_tokens, total_requests, status, paid_at) VALUES
('INV-ENT001-202603', 'ENT001', '2026-03', 'PRO',   399.00, 0.00, 399.00, 9040, 9,  'paid', '2026-03-01 09:00:00'),
('INV-ENT002-202603', 'ENT002', '2026-03', 'BASIC', 99.00,  0.00, 99.00,  600,  1,  'paid', '2026-03-01 10:00:00');

-- 动态 Tool 示例
INSERT INTO a_llm_tool (tool_name, tool_desc, input_schema, sql_template, table_alias, result_limit, status, remark) VALUES
('query_dynamic_sales_orders',
 '按客户名称查询销售订单列表，返回订单号、客户、订单日期、状态和金额',
 '{"type":"object","properties":{"customerName":{"type":"string","description":"客户名称关键字"}},"required":["customerName"]}',
 'SELECT o.order_no, o.customer_name, o.order_date, o.status, o.total_amount FROM b_sales_order o WHERE o.customer_name LIKE CONCAT(''%'', :customerName, ''%'') ORDER BY o.order_date DESC LIMIT 20',
 'o',
 20,
 'active',
 '动态 Tool 示例：销售订单查询'),
('query_inventory_lot_location',
 '按库存批次号查询物料或产品所在仓库、库位、可用库存、预留库存和在途库存',
 '{"type":"object","properties":{"lotNo":{"type":"string","description":"库存批次号，例如：L20260303"}},"required":["lotNo"]}',
 'SELECT i.lot_no, i.product_code, i.product_name, i.warehouse, i.location, i.available_qty, i.reserved_qty, i.in_transit_qty, i.unit FROM b_inventory i WHERE i.lot_no = :lotNo ORDER BY i.product_code ASC',
 'i',
 20,
 'active',
 '动态 Tool 示例：按库存批次号查询仓库库位');
