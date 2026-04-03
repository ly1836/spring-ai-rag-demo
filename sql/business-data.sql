-- ============================================================
-- ERP 系统数据库结构（DDL）及模拟数据（DML）
-- 根据 Tool 类中的 SQL 查询反向推导生成
-- 数据库: MySQL
-- ============================================================

-- ============================================================
-- 一、DDL —— 建表语句
-- ============================================================

-- ---------- 销售模块 (SalesTool) ----------

-- 销售订单主表
CREATE TABLE IF NOT EXISTS b_sales_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    order_date    DATE           NOT NULL COMMENT '下单日期',
    customer_name VARCHAR(100)   NOT NULL COMMENT '客户名称',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    total_amount  DECIMAL(14,2)           COMMENT '订单总金额',
    status        VARCHAR(20)             COMMENT '订单状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_customer_name (customer_name),
    INDEX idx_order_date (order_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '销售订单主表';

-- 销售订单明细表
CREATE TABLE IF NOT EXISTS b_sales_order_detail (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    product_code  VARCHAR(32)             COMMENT '产品编码',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    unit_price    DECIMAL(12,2)           COMMENT '单价',
    amount        DECIMAL(14,2)           COMMENT '金额',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_order_no (order_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '销售订单明细表';

-- 发货/物流表
CREATE TABLE IF NOT EXISTS b_shipment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    shipment_no   VARCHAR(32)    NOT NULL COMMENT '发货单号',
    ship_date     DATE                    COMMENT '发货日期',
    carrier       VARCHAR(50)             COMMENT '承运商',
    tracking_no   VARCHAR(64)             COMMENT '物流单号',
    shipped_qty   DECIMAL(12,2)           COMMENT '已发数量',
    status        VARCHAR(20)             COMMENT '发货状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_order_no (order_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '发货/物流表';

-- 应收账款表
CREATE TABLE IF NOT EXISTS b_accounts_receivable (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_name     VARCHAR(100)  NOT NULL COMMENT '客户名称',
    invoice_no        VARCHAR(32)            COMMENT '发票号',
    invoice_date      DATE                   COMMENT '发票日期',
    receivable_amount DECIMAL(14,2)          COMMENT '应收金额',
    received_amount   DECIMAL(14,2)          COMMENT '已收金额',
    ent_code          VARCHAR(32)   NOT NULL COMMENT '租户编码',
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '应收账款表';

-- ---------- 采购模块 (PurchaseTool) ----------

-- 采购订单主表
CREATE TABLE IF NOT EXISTS b_purchase_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no         VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    po_date       DATE           NOT NULL COMMENT '采购日期',
    supplier_name VARCHAR(100)   NOT NULL COMMENT '供应商名称',
    total_amount  DECIMAL(14,2)           COMMENT '总金额',
    status        VARCHAR(20)             COMMENT '订单状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_supplier_name (supplier_name),
    INDEX idx_po_date (po_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '采购订单主表';

-- 采购订单明细表
CREATE TABLE IF NOT EXISTS b_purchase_order_detail (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no         VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    product_code  VARCHAR(32)             COMMENT '产品编码',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    unit_price    DECIMAL(12,2)           COMMENT '单价',
    amount        DECIMAL(14,2)           COMMENT '金额',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_po_no (po_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '采购订单明细表';

-- 采购收货表
CREATE TABLE IF NOT EXISTS b_purchase_receive (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no         VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    receive_no    VARCHAR(32)    NOT NULL COMMENT '收货单号',
    receive_date  DATE                    COMMENT '收货日期',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    ordered_qty   DECIMAL(12,2)           COMMENT '订购数量',
    received_qty  DECIMAL(12,2)           COMMENT '已收数量',
    status        VARCHAR(20)             COMMENT '收货状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_po_no (po_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '采购收货表';

-- 应付账款表
CREATE TABLE IF NOT EXISTS b_accounts_payable (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_name   VARCHAR(100)  NOT NULL COMMENT '供应商名称',
    invoice_no      VARCHAR(32)            COMMENT '发票号',
    invoice_date    DATE                   COMMENT '发票日期',
    payable_amount  DECIMAL(14,2)          COMMENT '应付金额',
    paid_amount     DECIMAL(14,2)          COMMENT '已付金额',
    ent_code        VARCHAR(32)   NOT NULL COMMENT '租户编码',
    INDEX idx_supplier_name (supplier_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '应付账款表';

-- ---------- 生产模块 (ProductionTool) ----------

-- 生产工单表
CREATE TABLE IF NOT EXISTS b_work_order (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    wo_no              VARCHAR(32)    NOT NULL COMMENT '生产工单号',
    product_code       VARCHAR(32)             COMMENT '产品编码',
    product_name       VARCHAR(100)            COMMENT '产品名称',
    planned_qty        DECIMAL(12,2)           COMMENT '计划数量',
    completed_qty      DECIMAL(12,2)           COMMENT '完成数量',
    scrap_qty          DECIMAL(12,2)           COMMENT '报废数量',
    status             VARCHAR(20)             COMMENT '工单状态',
    planned_start_date DATE                    COMMENT '计划开始日期',
    planned_end_date   DATE                    COMMENT '计划结束日期',
    actual_start_date  DATE                    COMMENT '实际开始日期',
    actual_end_date    DATE                    COMMENT '实际结束日期',
    ent_code           VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_wo_no (wo_no),
    INDEX idx_product_code (product_code),
    INDEX idx_ent_code (ent_code)
) COMMENT '生产工单表';

-- 工单用料表
CREATE TABLE IF NOT EXISTS b_work_order_material (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    wo_no           VARCHAR(32)    NOT NULL COMMENT '生产工单号',
    material_code   VARCHAR(32)             COMMENT '物料编码',
    material_name   VARCHAR(100)            COMMENT '物料名称',
    required_qty    DECIMAL(12,2)           COMMENT '需求数量',
    issued_qty      DECIMAL(12,2)           COMMENT '已领数量',
    returned_qty    DECIMAL(12,2)           COMMENT '退料数量',
    unit            VARCHAR(10)             COMMENT '单位',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_wo_no (wo_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '工单用料表';

-- 工单工序报工表
CREATE TABLE IF NOT EXISTS b_work_order_routing (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    wo_no           VARCHAR(32)    NOT NULL COMMENT '生产工单号',
    operation_seq   INT                     COMMENT '工序序号',
    operation_name  VARCHAR(50)             COMMENT '工序名称',
    work_center     VARCHAR(50)             COMMENT '工作中心',
    planned_qty     DECIMAL(12,2)           COMMENT '计划数量',
    completed_qty   DECIMAL(12,2)           COMMENT '完成数量',
    scrap_qty       DECIMAL(12,2)           COMMENT '报废数量',
    status          VARCHAR(20)             COMMENT '工序状态',
    operator        VARCHAR(50)             COMMENT '操作员',
    report_date     DATE                    COMMENT '报工日期',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_wo_no (wo_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '工单工序报工表';

-- ---------- 仓库模块 (WarehouseTool) ----------

-- 库存表
CREATE TABLE IF NOT EXISTS b_inventory (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code    VARCHAR(32)    NOT NULL COMMENT '产品编码',
    product_name    VARCHAR(100)            COMMENT '产品名称',
    warehouse       VARCHAR(50)             COMMENT '仓库名称/编码',
    lot_no          VARCHAR(32)             COMMENT '批次号',
    available_qty   DECIMAL(12,2)           COMMENT '可用库存',
    reserved_qty    DECIMAL(12,2)           COMMENT '预留库存',
    in_transit_qty  DECIMAL(12,2)           COMMENT '在途库存',
    safety_stock    DECIMAL(12,2)           COMMENT '安全库存',
    unit            VARCHAR(10)             COMMENT '单位',
    location        VARCHAR(32)             COMMENT '库位',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_product_code (product_code),
    INDEX idx_warehouse (warehouse),
    INDEX idx_ent_code (ent_code)
) COMMENT '库存表';

-- 出入库流水表
CREATE TABLE IF NOT EXISTS b_stock_movement (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_no         VARCHAR(32)    NOT NULL COMMENT '流水单号',
    move_type       VARCHAR(20)             COMMENT '类型（入库/出库/调拨）',
    product_code    VARCHAR(32)             COMMENT '产品编码',
    product_name    VARCHAR(100)            COMMENT '产品名称',
    qty             DECIMAL(12,2)           COMMENT '数量',
    from_warehouse  VARCHAR(50)             COMMENT '源仓库',
    to_warehouse    VARCHAR(50)             COMMENT '目标仓库',
    move_date       DATE                    COMMENT '流转日期',
    reference_no    VARCHAR(32)             COMMENT '关联单号',
    remark          VARCHAR(200)            COMMENT '备注',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_product_code (product_code),
    INDEX idx_move_date (move_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '出入库流水表';

-- ---------- 质检模块 (QualityTool) ----------

-- 质检记录表
CREATE TABLE IF NOT EXISTS b_quality_inspection (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(32)    NOT NULL COMMENT '批次号',
    product_code     VARCHAR(32)             COMMENT '产品编码',
    product_name     VARCHAR(100)            COMMENT '产品名称',
    inspection_type  VARCHAR(30)             COMMENT '检验类型',
    inspection_date  DATE                    COMMENT '检验日期',
    inspector        VARCHAR(50)             COMMENT '检验员',
    sample_qty       DECIMAL(12,2)           COMMENT '抽样数量',
    pass_qty         DECIMAL(12,2)           COMMENT '合格数量',
    defect_qty       DECIMAL(12,2)           COMMENT '不良数量',
    result           VARCHAR(20)             COMMENT '检验结果（合格/不合格）',
    remark           VARCHAR(200)            COMMENT '备注',
    ent_code         VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_lot_no (lot_no),
    INDEX idx_product_code (product_code),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '质检记录表';

-- 质检不良明细表
CREATE TABLE IF NOT EXISTS b_quality_defect_detail (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(32)    NOT NULL COMMENT '批次号',
    defect_code      VARCHAR(32)             COMMENT '不良代码',
    defect_name      VARCHAR(50)             COMMENT '不良名称',
    defect_qty       DECIMAL(12,2)           COMMENT '不良数量',
    defect_level     VARCHAR(20)             COMMENT '不良等级',
    handling_method  VARCHAR(50)             COMMENT '处理方式',
    remark           VARCHAR(200)            COMMENT '备注',
    ent_code         VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_lot_no (lot_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '质检不良明细表';

-- ---------- 售后模块 (AfterSalesTool) ----------

-- 售后工单表
CREATE TABLE IF NOT EXISTS b_after_sales_ticket (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no         VARCHAR(32)    NOT NULL COMMENT '工单号',
    customer_name     VARCHAR(100)   NOT NULL COMMENT '客户名称',
    contact_person    VARCHAR(50)             COMMENT '联系人',
    contact_phone     VARCHAR(20)             COMMENT '联系电话',
    product_name      VARCHAR(100)            COMMENT '产品名称',
    serial_no         VARCHAR(64)             COMMENT '产品序列号',
    issue_type        VARCHAR(30)             COMMENT '问题类型',
    issue_description VARCHAR(500)            COMMENT '问题描述',
    root_cause        VARCHAR(500)            COMMENT '根本原因',
    solution          VARCHAR(500)            COMMENT '解决方案',
    status            VARCHAR(20)             COMMENT '工单状态',
    priority          VARCHAR(10)             COMMENT '优先级',
    created_date      DATE                    COMMENT '创建日期',
    resolved_date     DATE                    COMMENT '解决日期',
    handler           VARCHAR(50)             COMMENT '处理人',
    ent_code          VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_ticket_no (ticket_no),
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '售后工单表';

-- 退换货表
CREATE TABLE IF NOT EXISTS b_return_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_no       VARCHAR(32)    NOT NULL COMMENT '退货单号',
    customer_name   VARCHAR(100)            COMMENT '客户名称',
    product_code    VARCHAR(32)             COMMENT '产品编码',
    product_name    VARCHAR(100)            COMMENT '产品名称',
    return_type     VARCHAR(20)             COMMENT '退换类型（退货/换货）',
    qty             DECIMAL(12,2)           COMMENT '数量',
    reason          VARCHAR(200)            COMMENT '退换原因',
    status          VARCHAR(20)             COMMENT '状态',
    created_date    DATE                    COMMENT '创建日期',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_product_code (product_code),
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '退换货表';

-- ---------- 财务模块 (FinanceTool) ----------

-- 财务总账表
CREATE TABLE IF NOT EXISTS b_finance_ledger (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    type          VARCHAR(10)             COMMENT '类型（收入/支出）',
    amount        DECIMAL(14,2)           COMMENT '金额',
    ledger_date   DATE                    COMMENT '记账日期',
    remark        VARCHAR(200)            COMMENT '备注',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_ledger_date (ledger_date),
    INDEX idx_type (type),
    INDEX idx_ent_code (ent_code)
) COMMENT '财务总账表';

-- 收款记录表
CREATE TABLE IF NOT EXISTS b_payment_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no      VARCHAR(32)    NOT NULL COMMENT '收款单号',
    customer_name   VARCHAR(100)            COMMENT '客户名称',
    payment_date    DATE                    COMMENT '收款日期',
    amount          DECIMAL(14,2)           COMMENT '收款金额',
    payment_method  VARCHAR(30)             COMMENT '收款方式',
    reference_no    VARCHAR(32)             COMMENT '关联单号',
    remark          VARCHAR(200)            COMMENT '备注',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_payment_date (payment_date),
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '收款记录表';

-- ---------- 委外模块 (OutsourcingTool) ----------

-- 委外加工订单表
CREATE TABLE IF NOT EXISTS b_outsourcing_order (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    oo_no              VARCHAR(32)    NOT NULL COMMENT '委外订单号',
    supplier_name      VARCHAR(100)   NOT NULL COMMENT '供应商名称',
    product_name       VARCHAR(100)            COMMENT '产品名称',
    process_name       VARCHAR(50)             COMMENT '加工工序名称',
    qty                DECIMAL(12,2)           COMMENT '数量',
    unit_price         DECIMAL(12,2)           COMMENT '单价',
    total_amount       DECIMAL(14,2)           COMMENT '总金额',
    completed_qty      DECIMAL(12,2)           COMMENT '已完成数量',
    status             VARCHAR(20)             COMMENT '订单状态',
    delivery_date      DATE                    COMMENT '交货日期',
    actual_return_date DATE                    COMMENT '实际回货日期',
    ent_code           VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_oo_no (oo_no),
    INDEX idx_supplier_name (supplier_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '委外加工订单表';

-- 委外来料退料流水表
CREATE TABLE IF NOT EXISTS b_outsourcing_material_flow (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    oo_no         VARCHAR(32)    NOT NULL COMMENT '委外订单号',
    flow_type     VARCHAR(20)             COMMENT '流转类型（发料/退料/回货）',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    flow_date     DATE                    COMMENT '流转日期',
    remark        VARCHAR(200)            COMMENT '备注',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_oo_no (oo_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '委外来料退料流水表';


-- ============================================================
-- 二、DML —— 模拟数据（统一使用 ent_code = 'ENT001'）
-- ============================================================

-- ---------- 销售订单 ----------
INSERT INTO b_sales_order (order_no, order_date, customer_name, product_name, qty, total_amount, status, ent_code) VALUES
('SO20260301', '2026-03-01', '张三电子科技有限公司', '智能控制器A型', 100, 50000.00, '已发货', 'ENT001'),
('SO20260302', '2026-03-05', '张三电子科技有限公司', '传感器模组B型', 200, 30000.00, '生产中', 'ENT001'),
('SO20260303', '2026-03-08', '李四机械制造公司',     '智能控制器A型', 50,  25000.00, '已完成', 'ENT001'),
('SO20260304', '2026-03-12', '王五贸易有限公司',     '电源模块C型',   300, 45000.00, '待审核', 'ENT001'),
('SO20260305', '2026-03-15', '李四机械制造公司',     '传感器模组B型', 150, 22500.00, '已发货', 'ENT001'),
('SO20260306', '2026-03-18', '赵六科技股份公司',     '电机驱动板D型', 80,  64000.00, '生产中', 'ENT001'),
('SO20260307', '2026-03-20', '王五贸易有限公司',     '智能控制器A型', 120, 60000.00, '已完成', 'ENT001'),
('SO20260308', '2026-03-25', '赵六科技股份公司',     '电源模块C型',   200, 30000.00, '已发货', 'ENT001');

-- ---------- 销售订单明细 ----------
INSERT INTO b_sales_order_detail (order_no, product_code, product_name, qty, unit_price, amount, ent_code) VALUES
('SO20260301', 'P001', '智能控制器A型', 60,  500.00, 30000.00, 'ENT001'),
('SO20260301', 'P005', '配套线束',      100, 200.00, 20000.00, 'ENT001'),
('SO20260302', 'P002', '传感器模组B型', 200, 150.00, 30000.00, 'ENT001'),
('SO20260303', 'P001', '智能控制器A型', 50,  500.00, 25000.00, 'ENT001'),
('SO20260304', 'P003', '电源模块C型',   300, 150.00, 45000.00, 'ENT001'),
('SO20260305', 'P002', '传感器模组B型', 150, 150.00, 22500.00, 'ENT001'),
('SO20260306', 'P004', '电机驱动板D型', 80,  800.00, 64000.00, 'ENT001'),
('SO20260307', 'P001', '智能控制器A型', 120, 500.00, 60000.00, 'ENT001'),
('SO20260308', 'P003', '电源模块C型',   200, 150.00, 30000.00, 'ENT001');

-- ---------- 发货/物流 ----------
INSERT INTO b_shipment (order_no, shipment_no, ship_date, carrier, tracking_no, shipped_qty, status, ent_code) VALUES
('SO20260301', 'SH20260301', '2026-03-10', '顺丰速运', 'SF1234567890', 100, '已签收', 'ENT001'),
('SO20260303', 'SH20260302', '2026-03-15', '德邦物流', 'DB9876543210', 50,  '已签收', 'ENT001'),
('SO20260305', 'SH20260303', '2026-03-22', '顺丰速运', 'SF1122334455', 150, '运输中', 'ENT001'),
('SO20260307', 'SH20260304', '2026-03-26', '中通快递', 'ZT6677889900', 120, '已签收', 'ENT001'),
('SO20260308', 'SH20260305', '2026-03-28', '德邦物流', 'DB5566778899', 200, '运输中', 'ENT001');

-- ---------- 应收账款 ----------
INSERT INTO b_accounts_receivable (customer_name, invoice_no, invoice_date, receivable_amount, received_amount, ent_code) VALUES
('张三电子科技有限公司', 'INV20260301', '2026-03-02', 50000.00, 50000.00, 'ENT001'),
('张三电子科技有限公司', 'INV20260302', '2026-03-06', 30000.00, 10000.00, 'ENT001'),
('李四机械制造公司',     'INV20260303', '2026-03-09', 25000.00, 25000.00, 'ENT001'),
('王五贸易有限公司',     'INV20260304', '2026-03-13', 45000.00, 0.00,     'ENT001'),
('李四机械制造公司',     'INV20260305', '2026-03-16', 22500.00, 15000.00, 'ENT001'),
('赵六科技股份公司',     'INV20260306', '2026-03-19', 64000.00, 30000.00, 'ENT001'),
('王五贸易有限公司',     'INV20260307', '2026-03-21', 60000.00, 60000.00, 'ENT001'),
('赵六科技股份公司',     'INV20260308', '2026-03-26', 30000.00, 0.00,     'ENT001');

-- ---------- 采购订单 ----------
INSERT INTO b_purchase_order (po_no, po_date, supplier_name, total_amount, status, ent_code) VALUES
('PO20260301', '2026-03-01', '华芯微电子有限公司', 80000.00, '已收货', 'ENT001'),
('PO20260302', '2026-03-04', '华芯微电子有限公司', 25000.00, '部分收货', 'ENT001'),
('PO20260303', '2026-03-07', '恒达五金配件厂',     18000.00, '已下单', 'ENT001'),
('PO20260304', '2026-03-10', '鑫源塑胶材料公司',   32000.00, '已收货', 'ENT001'),
('PO20260305', '2026-03-15', '恒达五金配件厂',     12000.00, '已下单', 'ENT001');

-- ---------- 采购订单明细 ----------
INSERT INTO b_purchase_order_detail (po_no, product_code, product_name, qty, unit_price, amount, ent_code) VALUES
('PO20260301', 'M001', 'MCU芯片STM32',    500,  120.00, 60000.00, 'ENT001'),
('PO20260301', 'M002', '电容0805-100nF',   5000, 4.00,   20000.00, 'ENT001'),
('PO20260302', 'M003', '温度传感器NTC10K', 1000, 25.00,  25000.00, 'ENT001'),
('PO20260303', 'M004', '铝合金外壳',       200,  90.00,  18000.00, 'ENT001'),
('PO20260304', 'M005', 'ABS塑料颗粒',      800,  40.00,  32000.00, 'ENT001'),
('PO20260305', 'M006', '不锈钢螺丝M3',    3000, 4.00,   12000.00, 'ENT001');

-- ---------- 采购收货 ----------
INSERT INTO b_purchase_receive (po_no, receive_no, receive_date, product_name, ordered_qty, received_qty, status, ent_code) VALUES
('PO20260301', 'RCV20260301', '2026-03-08', 'MCU芯片STM32',    500,  500,  '已验收', 'ENT001'),
('PO20260301', 'RCV20260302', '2026-03-08', '电容0805-100nF',   5000, 5000, '已验收', 'ENT001'),
('PO20260302', 'RCV20260303', '2026-03-12', '温度传感器NTC10K', 1000, 600,  '部分收货', 'ENT001'),
('PO20260304', 'RCV20260304', '2026-03-18', 'ABS塑料颗粒',      800,  800,  '已验收', 'ENT001');

-- ---------- 应付账款 ----------
INSERT INTO b_accounts_payable (supplier_name, invoice_no, invoice_date, payable_amount, paid_amount, ent_code) VALUES
('华芯微电子有限公司', 'PINV20260301', '2026-03-09', 80000.00, 80000.00, 'ENT001'),
('华芯微电子有限公司', 'PINV20260302', '2026-03-13', 25000.00, 10000.00, 'ENT001'),
('恒达五金配件厂',     'PINV20260303', '2026-03-08', 18000.00, 0.00,     'ENT001'),
('鑫源塑胶材料公司',   'PINV20260304', '2026-03-19', 32000.00, 32000.00, 'ENT001'),
('恒达五金配件厂',     'PINV20260305', '2026-03-16', 12000.00, 0.00,     'ENT001');

-- ---------- 生产工单 ----------
INSERT INTO b_work_order (wo_no, product_code, product_name, planned_qty, completed_qty, scrap_qty, status, planned_start_date, planned_end_date, actual_start_date, actual_end_date, ent_code) VALUES
('WO20260301', 'P001', '智能控制器A型', 200, 200, 3,  '已完工', '2026-03-01', '2026-03-10', '2026-03-01', '2026-03-09', 'ENT001'),
('WO20260302', 'P002', '传感器模组B型', 500, 350, 5,  '生产中', '2026-03-05', '2026-03-20', '2026-03-05', NULL,         'ENT001'),
('WO20260303', 'P003', '电源模块C型',   300, 0,   0,  '待排产', '2026-03-15', '2026-03-25', NULL,         NULL,         'ENT001'),
('WO20260304', 'P004', '电机驱动板D型', 100, 60,  2,  '生产中', '2026-03-10', '2026-03-22', '2026-03-10', NULL,         'ENT001'),
('WO20260305', 'P001', '智能控制器A型', 150, 150, 1,  '已完工', '2026-03-12', '2026-03-18', '2026-03-12', '2026-03-17', 'ENT001');

-- ---------- 工单用料 ----------
INSERT INTO b_work_order_material (wo_no, material_code, material_name, required_qty, issued_qty, returned_qty, unit, ent_code) VALUES
('WO20260301', 'M001', 'MCU芯片STM32',    200, 205, 2, '个', 'ENT001'),
('WO20260301', 'M002', '电容0805-100nF',   2000, 2000, 0, '个', 'ENT001'),
('WO20260301', 'M004', '铝合金外壳',       200, 200, 0, '个', 'ENT001'),
('WO20260302', 'M003', '温度传感器NTC10K', 500, 400, 10, '个', 'ENT001'),
('WO20260302', 'M002', '电容0805-100nF',   1500, 1200, 0, '个', 'ENT001'),
('WO20260304', 'M001', 'MCU芯片STM32',    100, 70, 0, '个', 'ENT001'),
('WO20260304', 'M005', 'ABS塑料颗粒',      50, 40, 0, 'kg', 'ENT001');

-- ---------- 工单工序报工 ----------
INSERT INTO b_work_order_routing (wo_no, operation_seq, operation_name, work_center, planned_qty, completed_qty, scrap_qty, status, operator, report_date, ent_code) VALUES
('WO20260301', 10, 'SMT贴片',   '贴片车间',   200, 200, 1, '已完成', '陈工', '2026-03-03', 'ENT001'),
('WO20260301', 20, '回流焊接',  '焊接车间',   200, 200, 1, '已完成', '林工', '2026-03-05', 'ENT001'),
('WO20260301', 30, '功能测试',  '测试车间',   200, 200, 1, '已完成', '周工', '2026-03-07', 'ENT001'),
('WO20260301', 40, '组装包装',  '包装车间',   200, 200, 0, '已完成', '吴工', '2026-03-09', 'ENT001'),
('WO20260302', 10, 'SMT贴片',   '贴片车间',   500, 400, 2, '已完成', '陈工', '2026-03-08', 'ENT001'),
('WO20260302', 20, '回流焊接',  '焊接车间',   500, 350, 3, '生产中', '林工', '2026-03-12', 'ENT001'),
('WO20260302', 30, '功能测试',  '测试车间',   500, 0,   0, '待开工', NULL,   NULL,         'ENT001'),
('WO20260304', 10, '线路板制作', '制板车间',   100, 80,  1, '已完成', '张工', '2026-03-14', 'ENT001'),
('WO20260304', 20, '元件焊接',  '焊接车间',   100, 60,  1, '生产中', '林工', '2026-03-18', 'ENT001'),
('WO20260304', 30, '老化测试',  '测试车间',   100, 0,   0, '待开工', NULL,   NULL,         'ENT001');

-- ---------- 库存 ----------
INSERT INTO b_inventory (product_code, product_name, warehouse, lot_no, available_qty, reserved_qty, in_transit_qty, safety_stock, unit, location, ent_code) VALUES
('P001', '智能控制器A型', '成品仓',   'L20260301', 180, 50, 0,   100, '台', 'A-01-01', 'ENT001'),
('P001', '智能控制器A型', '华东分仓', 'L20260305', 60,  10, 30,  50,  '台', 'B-02-01', 'ENT001'),
('P002', '传感器模组B型', '成品仓',   'L20260302', 320, 80, 0,   200, '个', 'A-02-01', 'ENT001'),
('P003', '电源模块C型',   '成品仓',   'L20260303', 50,  20, 100, 150, '个', 'A-03-01', 'ENT001'),
('P004', '电机驱动板D型', '成品仓',   'L20260304', 30,  0,  0,   50,  '台', 'A-04-01', 'ENT001'),
('P005', '配套线束',      '成品仓',   'L20260306', 500, 100, 0,  200, '根', 'A-05-01', 'ENT001'),
('M001', 'MCU芯片STM32',    '原料仓', 'L20260307', 120, 30, 200, 100, '个', 'C-01-01', 'ENT001'),
('M002', '电容0805-100nF',   '原料仓', 'L20260308', 800, 0,  0,   1000,'个', 'C-01-02', 'ENT001'),
('M003', '温度传感器NTC10K', '原料仓', 'L20260309', 380, 50, 400, 300, '个', 'C-02-01', 'ENT001'),
('M004', '铝合金外壳',       '原料仓', 'L20260310', 20,  0,  0,   80,  '个', 'C-03-01', 'ENT001'),
('M005', 'ABS塑料颗粒',      '原料仓', 'L20260311', 500, 100, 0,  200, 'kg', 'C-04-01', 'ENT001');

-- ---------- 出入库流水 ----------
INSERT INTO b_stock_movement (move_no, move_type, product_code, product_name, qty, from_warehouse, to_warehouse, move_date, reference_no, remark, ent_code) VALUES
('MV20260301', '入库', 'P001', '智能控制器A型', 200, NULL,      '成品仓',   '2026-03-09', 'WO20260301', '工单完工入库', 'ENT001'),
('MV20260302', '出库', 'P001', '智能控制器A型', 100, '成品仓',   NULL,       '2026-03-10', 'SO20260301', '销售发货',     'ENT001'),
('MV20260303', '出库', 'M001', 'MCU芯片STM32',  205, '原料仓',  NULL,       '2026-03-02', 'WO20260301', '生产领料',     'ENT001'),
('MV20260304', '入库', 'M001', 'MCU芯片STM32',  500, NULL,      '原料仓',   '2026-03-08', 'PO20260301', '采购入库',     'ENT001'),
('MV20260305', '调拨', 'P001', '智能控制器A型', 30,  '成品仓',   '华东分仓', '2026-03-12', NULL,         '区域调拨',     'ENT001'),
('MV20260306', '出库', 'P002', '传感器模组B型', 150, '成品仓',   NULL,       '2026-03-22', 'SO20260305', '销售发货',     'ENT001'),
('MV20260307', '入库', 'M003', '温度传感器NTC10K', 600, NULL,   '原料仓',   '2026-03-12', 'PO20260302', '采购入库',     'ENT001');

-- ---------- 质检记录 ----------
INSERT INTO b_quality_inspection (lot_no, product_code, product_name, inspection_type, inspection_date, inspector, sample_qty, pass_qty, defect_qty, result, remark, ent_code) VALUES
('L20260301', 'P001', '智能控制器A型', '成品检验', '2026-03-09', '周工', 50, 49, 1, '合格', '不良率2%，在控',       'ENT001'),
('L20260302', 'P002', '传感器模组B型', '成品检验', '2026-03-14', '周工', 80, 78, 2, '合格', '轻微外观瑕疵',         'ENT001'),
('L20260307', 'M001', 'MCU芯片STM32', '来料检验', '2026-03-08', '刘工', 50, 50, 0, '合格', NULL,                    'ENT001'),
('L20260309', 'M003', '温度传感器NTC10K', '来料检验', '2026-03-12', '刘工', 60, 55, 5, '不合格', '精度超差5个',    'ENT001'),
('L20260310', 'M004', '铝合金外壳',    '来料检验', '2026-03-20', '刘工', 30, 28, 2, '合格', '表面划痕2个',          'ENT001'),
('L20260304', 'P004', '电机驱动板D型', '成品检验', '2026-03-18', '周工', 30, 28, 2, '合格', '功能测试有2个不通过',   'ENT001'),
('L20260303', 'P003', '电源模块C型',   '首件检验', '2026-03-15', '周工', 5,  5,  0, '合格', '首件OK',               'ENT001');

-- ---------- 质检不良明细 ----------
INSERT INTO b_quality_defect_detail (lot_no, defect_code, defect_name, defect_qty, defect_level, handling_method, remark, ent_code) VALUES
('L20260301', 'D001', '功能异常',   1, '严重', '返修',   '通讯模块无响应',       'ENT001'),
('L20260302', 'D002', '外观瑕疵',   2, '轻微', '让步接收', '外壳轻微划痕',      'ENT001'),
('L20260309', 'D003', '精度超差',   5, '严重', '退货',   '温度测量偏差超过±2℃', 'ENT001'),
('L20260310', 'D002', '外观瑕疵',   2, '轻微', '返修',   '阳极氧化层划伤',      'ENT001'),
('L20260304', 'D001', '功能异常',   1, '严重', '返修',   'PWM输出异常',         'ENT001'),
('L20260304', 'D004', '焊接不良',   1, '一般', '返修',   '虚焊导致短路',        'ENT001');

-- ---------- 售后工单 ----------
INSERT INTO b_after_sales_ticket (ticket_no, customer_name, contact_person, contact_phone, product_name, serial_no, issue_type, issue_description, root_cause, solution, status, priority, created_date, resolved_date, handler, ent_code) VALUES
('AS20260301', '张三电子科技有限公司', '张经理', '13800138001', '智能控制器A型', 'SN20260100001', '产品故障', '设备运行3天后通讯中断',     '通讯芯片虚焊',   '更换主板并重新焊接', '已解决', '高', '2026-03-12', '2026-03-14', '王技术', 'ENT001'),
('AS20260302', '李四机械制造公司',     '李助理', '13900139002', '智能控制器A型', 'SN20260100025', '使用咨询', '如何配置多设备级联模式',     NULL,              '提供技术文档和远程指导', '已解决', '中', '2026-03-15', '2026-03-15', '赵技术', 'ENT001'),
('AS20260303', '赵六科技股份公司',     '赵总',   '13700137003', '电机驱动板D型', 'SN20260400010', '产品故障', '电机驱动板过热保护频繁触发', '散热设计不足',   NULL,                     '处理中', '高', '2026-03-20', NULL,         '王技术', 'ENT001'),
('AS20260304', '王五贸易有限公司',     '王主管', '13600136004', '电源模块C型',   'SN20260300005', '退换货',   '收到货物外包装破损，产品外壳变形', '运输磕碰', '安排换货',           '已解决', '中', '2026-03-22', '2026-03-25', '赵技术', 'ENT001'),
('AS20260305', '张三电子科技有限公司', '张经理', '13800138001', '传感器模组B型', 'SN20260200050', '产品故障', '传感器数据漂移严重',         NULL,              NULL,                     '待处理', '高', '2026-03-28', NULL,         NULL,     'ENT001');

-- ---------- 退换货 ----------
INSERT INTO b_return_order (return_no, customer_name, product_code, product_name, return_type, qty, reason, status, created_date, ent_code) VALUES
('RT20260301', '王五贸易有限公司',     'P003', '电源模块C型',   '换货', 2,  '外包装破损导致产品变形', '已完成', '2026-03-22', 'ENT001'),
('RT20260302', '张三电子科技有限公司', 'P001', '智能控制器A型', '退货', 1,  '通讯模块故障无法修复',   '已完成', '2026-03-14', 'ENT001'),
('RT20260303', '赵六科技股份公司',     'P004', '电机驱动板D型', '退货', 3,  '过热保护问题批次退货',   '处理中', '2026-03-25', 'ENT001');

-- ---------- 财务总账 ----------
INSERT INTO b_finance_ledger (type, amount, ledger_date, remark, ent_code) VALUES
('收入', 50000.00,  '2026-03-02', '张三电子-SO20260301货款',     'ENT001'),
('收入', 25000.00,  '2026-03-10', '李四机械-SO20260303货款',     'ENT001'),
('收入', 10000.00,  '2026-03-15', '张三电子-SO20260302预付款',   'ENT001'),
('收入', 60000.00,  '2026-03-22', '王五贸易-SO20260307货款',     'ENT001'),
('收入', 15000.00,  '2026-03-25', '李四机械-SO20260305部分付款', 'ENT001'),
('收入', 30000.00,  '2026-03-28', '赵六科技-SO20260306部分付款', 'ENT001'),
('支出', 80000.00,  '2026-03-10', '华芯微电子-PO20260301付款',   'ENT001'),
('支出', 10000.00,  '2026-03-15', '华芯微电子-PO20260302部分付款', 'ENT001'),
('支出', 32000.00,  '2026-03-20', '鑫源塑胶-PO20260304付款',     'ENT001'),
('支出', 15000.00,  '2026-03-05', '3月份员工工资预付',            'ENT001'),
('支出', 5000.00,   '2026-03-08', '办公用品采购',                 'ENT001'),
('支出', 8000.00,   '2026-03-18', '设备维护保养费用',             'ENT001');

-- ---------- 收款记录 ----------
INSERT INTO b_payment_record (payment_no, customer_name, payment_date, amount, payment_method, reference_no, remark, ent_code) VALUES
('PAY20260301', '张三电子科技有限公司', '2026-03-02', 50000.00, '银行转账', 'SO20260301', '全额付款',   'ENT001'),
('PAY20260302', '李四机械制造公司',     '2026-03-10', 25000.00, '银行转账', 'SO20260303', '全额付款',   'ENT001'),
('PAY20260303', '张三电子科技有限公司', '2026-03-15', 10000.00, '银行转账', 'SO20260302', '预付30%',    'ENT001'),
('PAY20260304', '王五贸易有限公司',     '2026-03-22', 60000.00, '承兑汇票', 'SO20260307', '全额付款',   'ENT001'),
('PAY20260305', '李四机械制造公司',     '2026-03-25', 15000.00, '银行转账', 'SO20260305', '部分付款',   'ENT001'),
('PAY20260306', '赵六科技股份公司',     '2026-03-28', 30000.00, '银行转账', 'SO20260306', '预付50%',    'ENT001');

-- ---------- 委外加工订单 ----------
INSERT INTO b_outsourcing_order (oo_no, supplier_name, product_name, process_name, qty, unit_price, total_amount, completed_qty, status, delivery_date, actual_return_date, ent_code) VALUES
('OO20260301', '恒达五金配件厂',     '铝合金外壳',   'CNC精加工',   200, 45.00, 9000.00,  200, '已完成', '2026-03-12', '2026-03-11', 'ENT001'),
('OO20260302', '恒达五金配件厂',     '铝合金外壳',   '阳极氧化',   200, 30.00, 6000.00,  200, '已完成', '2026-03-15', '2026-03-14', 'ENT001'),
('OO20260303', '鑫源塑胶材料公司',   '驱动板外壳',   '注塑成型',   100, 25.00, 2500.00,  60,  '加工中', '2026-03-22', NULL,         'ENT001'),
('OO20260304', '华芯微电子有限公司', '传感器模组B型', 'PCBA焊接',   300, 15.00, 4500.00,  0,   '待加工', '2026-03-28', NULL,         'ENT001');

-- ---------- 委外来料退料流水 ----------
INSERT INTO b_outsourcing_material_flow (oo_no, flow_type, product_name, qty, flow_date, remark, ent_code) VALUES
('OO20260301', '发料', '铝合金外壳毛坯', 210, '2026-03-05', '含10个余量', 'ENT001'),
('OO20260301', '回货', '铝合金外壳',     200, '2026-03-11', '加工完成',   'ENT001'),
('OO20260301', '退料', '铝合金外壳毛坯', 8,   '2026-03-11', '余料退回',   'ENT001'),
('OO20260302', '发料', '铝合金外壳',     200, '2026-03-12', NULL,         'ENT001'),
('OO20260302', '回货', '铝合金外壳',     200, '2026-03-14', '氧化完成',   'ENT001'),
('OO20260303', '发料', 'ABS塑料颗粒',    50,  '2026-03-16', NULL,         'ENT001'),
('OO20260303', '回货', '驱动板外壳',     60,  '2026-03-20', '第一批回货', 'ENT001');
