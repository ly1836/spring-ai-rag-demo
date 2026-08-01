/**
 * ChartAdapter 的零浏览器 fixture 验证脚本。
 * 使用假的 ECharts 实例检查 23 种类型均能构造 option。
 */
const fs = require('fs');
const vm = require('vm');

/** 收集每次 setOption 接收的 option。 */
const options = [];

/** 创建测试用容器。 */
function createContainer() {
  return {
    textContent: '',
    classList: { add() {}, remove() {} },
    contains(target) { return target === this; }
  };
}

/** 提供 ChartAdapter 所需的最小浏览器环境。 */
global.window = {
  document: { documentElement: {} },
  getComputedStyle() { return { getPropertyValue() { return ''; } }; },
  echarts: {
    graphic: {
      clipRectByRect(shape) { return shape; }
    },
    init() {
      return {
        setOption(option) {
          if (!option || !Array.isArray(option.series) || option.series.length === 0) {
            throw new Error('未生成有效 series');
          }
          options.push(option);
        },
        dispose() {},
        resize() {}
      };
    }
  }
};

/** 加载生产 ChartAdapter。 */
const adapterSource = fs.readFileSync('src/main/resources/static/chart-adapter.js', 'utf8');
vm.runInThisContext(adapterSource, { filename: 'chart-adapter.js' });

/** 全部 23 种协议类型。 */
const types = [
  'donut', 'sunburst', 'bar', 'waterfall', 'bullet', 'area', 'step', 'radar',
  'scatter', 'bubble', 'histogram', 'boxplot', 'heatmap', 'sankey', 'treemap',
  'gantt', 'funnel', 'word-cloud', 'gauge', 'liquid-fill', 'parallel', 'line', 'pie'
];

/** 构造指定类型的最小合法 ChartSpec。 */
function fixture(type) {
  const rows = [{
    id: 'root', parentId: '', name: '项目A', category: 'A', value: 10,
    actual: 8, target: 9, range: 12, x: 1, y: 2, size: 3, visualSize: 28,
    p1: 1, p2: 2, p3: 3, p1Max: 4, p2Max: 4, p3Max: 4,
    source: '节点A', linkTarget: '节点B', start: '2026-07-01', end: '2026-07-02',
    progress: 0.5, normalized: 0.5, binLabel: '0 - 10', count: 1,
    min: 1, q1: 2, median: 3, q3: 4, max: 5, outliers: []
  }];
  const encoding = {
    id: ['id'], parentId: ['parentId'], name: ['name'], category: ['category'],
    value: ['value'], actual: ['actual'], target: ['target'], range: ['range'],
    x: ['x'], y: ['y'], size: ['size'], visualSize: ['visualSize'],
    indicator: ['p1', 'p2', 'p3'], indicatorMax: ['p1Max', 'p2Max', 'p3Max'],
    source: ['source'], start: ['start'], end: ['end'], progress: ['progress'],
    parallel: ['p1', 'p2', 'p3'], normalized: ['normalized']
  };
  if (type === 'sankey') encoding.target = ['linkTarget'];
  if (type === 'histogram') {
    encoding.category = ['binLabel'];
    encoding.value = ['count'];
  }
  if (type === 'boxplot') {
    encoding.value = ['min', 'q1', 'median', 'q3', 'max'];
    encoding.outlier = ['outliers'];
  }
  if (type === 'waterfall') {
    rows[0].base = 0;
    rows[0].increase = 10;
    rows[0].decrease = 0;
    encoding.base = ['base'];
    encoding.increase = ['increase'];
    encoding.decrease = ['decrease'];
  }
  return {
    schemaVersion: '1.0',
    chartId: 'fixture-' + type,
    type,
    title: '<script>不会执行</script>',
    subtitle: null,
    dataset: {
      dimensions: Object.keys(rows[0]).slice(0, 32).map(key => ({
        key, label: key, dataType: typeof rows[0][key] === 'number' ? 'number' : 'string', unit: null
      })),
      rows
    },
    encoding,
    options: { orientation: 'vertical', min: 0, max: 100, showLabel: true },
    source: { toolNames: ['query_fixture'] }
  };
}

/** 逐类型通过公开 render API 验证 option 构造。 */
types.forEach(type => {
  const rendered = window.ChartAdapter.render(createContainer(), fixture(type));
  if (!rendered) throw new Error(type + ' fixture 渲染失败');
});

/** 折线图的 series 通道必须生成独立系列并共享对齐后的类别轴。 */
const multiSeriesLine = fixture('line');
multiSeriesLine.dataset.rows = [
  { month: '一月', amount: 10, group: '华东' },
  { month: '二月', amount: 15, group: '华东' },
  { month: '一月', amount: 8, group: '华南' },
  { month: '二月', amount: 12, group: '华南' }
];
multiSeriesLine.dataset.dimensions = [
  { key: 'month', label: '月份', dataType: 'string', unit: null },
  { key: 'amount', label: '金额', dataType: 'number', unit: '元' },
  { key: 'group', label: '区域', dataType: 'string', unit: null }
];
multiSeriesLine.encoding = { x: ['month'], y: ['amount'], series: ['group'] };
if (!window.ChartAdapter.render(createContainer(), multiSeriesLine)) {
  throw new Error('折线图多系列 fixture 渲染失败');
}
const multiSeriesLineOption = options[types.length];
if (multiSeriesLineOption.series.length !== 2 ||
    multiSeriesLineOption.series[0].name !== '华东' ||
    multiSeriesLineOption.series[1].data[1] !== 12) {
  throw new Error('折线图未按 series 通道拆分并对齐数据');
}

/** 气泡图的 series 通道必须生成独立散点系列。 */
const multiSeriesBubble = fixture('bubble');
multiSeriesBubble.dataset.rows = [
  { x: 1, y: 2, visualSize: 18, group: '现有客户' },
  { x: 3, y: 4, visualSize: 28, group: '潜在客户' }
];
multiSeriesBubble.dataset.dimensions = [
  { key: 'x', label: '横轴', dataType: 'number', unit: null },
  { key: 'y', label: '纵轴', dataType: 'number', unit: null },
  { key: 'visualSize', label: '气泡大小', dataType: 'number', unit: null },
  { key: 'group', label: '客户类型', dataType: 'string', unit: null }
];
multiSeriesBubble.encoding.series = ['group'];
if (!window.ChartAdapter.render(createContainer(), multiSeriesBubble)) {
  throw new Error('气泡图多系列 fixture 渲染失败');
}
const multiSeriesBubbleOption = options[types.length + 1];
if (multiSeriesBubbleOption.series.length !== 2 ||
    multiSeriesBubbleOption.series[1].name !== '潜在客户') {
  throw new Error('气泡图未按 series 通道拆分数据');
}

/** 无 series 通道时必须逐行保留重复类目，不能只取第一条数据。 */
const repeatedCategoryBar = fixture('bar');
repeatedCategoryBar.dataset.rows = [
  { category: 'A', value: 1 },
  { category: 'A', value: 2 }
];
repeatedCategoryBar.dataset.dimensions = [
  { key: 'category', label: '类别', dataType: 'string', unit: null },
  { key: 'value', label: '数值', dataType: 'number', unit: null }
];
repeatedCategoryBar.encoding = { category: ['category'], value: ['value'] };
if (!window.ChartAdapter.render(createContainer(), repeatedCategoryBar)) {
  throw new Error('重复类目单系列 fixture 渲染失败');
}
const repeatedCategoryBarOption = options[types.length + 2];
if (repeatedCategoryBarOption.xAxis.data.length !== 2 ||
    repeatedCategoryBarOption.series[0].data[0] !== 1 ||
    repeatedCategoryBarOption.series[0].data[1] !== 2) {
  throw new Error('无 series 通道时重复类目数据被静默丢弃');
}

/** 多系列相同类目重复出现时按出现顺序对齐，不能只保留第一条数据。 */
const repeatedMultiSeriesLine = fixture('line');
repeatedMultiSeriesLine.dataset.rows = [
  { month: '一月', amount: 10, group: '华东' },
  { month: '一月', amount: 20, group: '华东' },
  { month: '一月', amount: 8, group: '华南' }
];
repeatedMultiSeriesLine.dataset.dimensions = [
  { key: 'month', label: '月份', dataType: 'string', unit: null },
  { key: 'amount', label: '金额', dataType: 'number', unit: '元' },
  { key: 'group', label: '区域', dataType: 'string', unit: null }
];
repeatedMultiSeriesLine.encoding = { x: ['month'], y: ['amount'], series: ['group'] };
if (!window.ChartAdapter.render(createContainer(), repeatedMultiSeriesLine)) {
  throw new Error('重复类目多系列 fixture 渲染失败');
}
const repeatedMultiSeriesLineOption = options[types.length + 3];
if (repeatedMultiSeriesLineOption.xAxis.data.length !== 2 ||
    repeatedMultiSeriesLineOption.series[0].data[0] !== 10 ||
    repeatedMultiSeriesLineOption.series[0].data[1] !== 20 ||
    repeatedMultiSeriesLineOption.series[1].data[0] !== 8 ||
    repeatedMultiSeriesLineOption.series[1].data[1] !== null) {
  throw new Error('多 series 通道时重复类目数据被静默丢弃');
}

/** 长系列名称即使展示前缀相同，也必须按完整原值保持为两个系列。 */
const longSeriesBar = fixture('bar');
const longPrefix = '长系列名称'.repeat(30);
longSeriesBar.dataset.rows = [
  { category: 'A', value: 1, group: longPrefix + '甲' },
  { category: 'A', value: 2, group: longPrefix + '乙' }
];
longSeriesBar.dataset.dimensions = [
  { key: 'category', label: '类别', dataType: 'string', unit: null },
  { key: 'value', label: '数值', dataType: 'number', unit: null },
  { key: 'group', label: '系列', dataType: 'string', unit: null }
];
longSeriesBar.encoding = { category: ['category'], value: ['value'], series: ['group'] };
if (!window.ChartAdapter.render(createContainer(), longSeriesBar)) {
  throw new Error('长系列名称 fixture 渲染失败');
}
const longSeriesBarOption = options[types.length + 4];
if (longSeriesBarOption.series.length !== 2) {
  throw new Error('长系列名称因展示截断被错误合并');
}

/** 长桑基节点名称必须使用独立内部 ID，不能因展示截断合并节点或边。 */
const longNodeSankey = fixture('sankey');
longNodeSankey.dataset.rows = [
  { source: longPrefix + '甲', linkTarget: '目标甲', value: 1 },
  { source: longPrefix + '乙', linkTarget: '目标乙', value: 2 }
];
longNodeSankey.dataset.dimensions = [
  { key: 'source', label: '来源', dataType: 'string', unit: null },
  { key: 'linkTarget', label: '目标', dataType: 'string', unit: null },
  { key: 'value', label: '数值', dataType: 'number', unit: null }
];
longNodeSankey.encoding = { source: ['source'], target: ['linkTarget'], value: ['value'] };
if (!window.ChartAdapter.render(createContainer(), longNodeSankey)) {
  throw new Error('长桑基节点 fixture 渲染失败');
}
const longNodeSankeyOption = options[types.length + 5];
if (longNodeSankeyOption.series[0].data.length !== 4 ||
    longNodeSankeyOption.series[0].links[0].source ===
      longNodeSankeyOption.series[0].links[1].source) {
  throw new Error('长桑基节点因展示截断被错误合并');
}

/** 热力图空值必须保留为空，不能在色阶范围中被转换成零。 */
const nullableHeatmap = fixture('heatmap');
nullableHeatmap.dataset.rows = [
  { x: '一月', y: '华东', value: null },
  { x: '二月', y: '华东', value: 5 }
];
nullableHeatmap.dataset.dimensions = [
  { key: 'x', label: '月份', dataType: 'string', unit: null },
  { key: 'y', label: '区域', dataType: 'string', unit: null },
  { key: 'value', label: '数值', dataType: 'number', unit: null }
];
nullableHeatmap.encoding = { x: ['x'], y: ['y'], value: ['value'] };
if (!window.ChartAdapter.render(createContainer(), nullableHeatmap)) {
  throw new Error('热力图空值 fixture 渲染失败');
}
const nullableHeatmapOption = options[types.length + 6];
if (nullableHeatmapOption.visualMap.min !== 5 || nullableHeatmapOption.visualMap.max !== 5 ||
    nullableHeatmapOption.series[0].data[0][2] !== null) {
  throw new Error('热力图空值被错误转换为零');
}

/** 使用后端编译测试共用的 fixture 验证字符串类别热力图协议。 */
const sharedPipelineFixture = JSON.parse(
  fs.readFileSync('src/test/resources/chart-pipeline-fixture.json', 'utf8'));
if (!window.ChartAdapter.render(createContainer(), sharedPipelineFixture.chartSpec)) {
  throw new Error('共享热力图 fixture 渲染失败');
}
const sharedHeatmapOption = options[types.length + 7];
if (sharedHeatmapOption.xAxis.data.join(',') !== '一月,二月' ||
    sharedHeatmapOption.yAxis.data.join(',') !== '华东,华南' ||
    sharedHeatmapOption.series[0].data.length !== sharedPipelineFixture.sourceRows.length) {
  throw new Error('共享热力图 fixture 前后端协议不一致');
}

/** 使用共享碰撞 fixture 验证前端严格按 encoding 读取编译器派生字段。 */
sharedPipelineFixture.collisionSpecs.forEach(spec => {
  if (!window.ChartAdapter.render(createContainer(), spec)) {
    throw new Error('共享派生字段碰撞 fixture 渲染失败');
  }
});
const sharedBubbleCollisionOption = options[types.length + 8];
const sharedLiquidCollisionOption = options[types.length + 9];
if (sharedBubbleCollisionOption.series[0].data[0][2] !== 35 ||
    sharedLiquidCollisionOption.series[0].data[0] !== 0.5) {
  throw new Error('共享派生字段碰撞 fixture 未按 encoding 取值');
}

/** 官方词云和水位图 custom series 必须收到无坐标系声明和独立 itemPayload。 */
['word-cloud', 'liquid-fill'].forEach(type => {
  const series = options[types.indexOf(type)].series[0];
  if (series.coordinateSystem !== 'none' || !series.itemPayload) {
    throw new Error(type + ' 缺少官方 custom series 配置');
  }
});

/** 甘特图必须使用时间横轴、类别纵轴和应用内固定水平范围渲染函数。 */
const ganttSeries = options[types.indexOf('gantt')].series[0];
const ganttBaseOption = options[types.indexOf('gantt')];
if (typeof ganttSeries.renderItem !== 'function' || ganttBaseOption.xAxis.type !== 'time' ||
    ganttBaseOption.yAxis.type !== 'category' || !Array.isArray(ganttSeries.encode.x) ||
    ganttSeries.encode.y !== 0) {
  throw new Error('gantt 未使用水平时间范围配置');
}
const ganttRangeData = ganttSeries.data[0];
const ganttRangeOrigin = ganttRangeData[1];
const ganttShape = ganttSeries.renderItem(
  { coordSys: { x: 0, y: 0, width: 400, height: 120 } },
  {
    value(index) { return ganttRangeData[index]; },
    coord(point) { return [(point[0] - ganttRangeOrigin) / 864000, point[1] * 40 + 20]; },
    size() { return [0, 40]; },
    visual() { return '#4f46e5'; }
  });
if (!ganttShape || ganttShape.shape.width <= ganttShape.shape.height) {
  throw new Error('gantt 未绘制水平任务范围条');
}
/** 甘特图存在 progress 通道时必须叠加已完成范围条。 */
const ganttOption = options[types.indexOf('gantt')];
const ganttProgressData = ganttOption.series[1] && ganttOption.series[1].data[0];
if (ganttOption.series.length !== 2 || !ganttProgressData ||
    ganttProgressData[2] !==
      ganttProgressData[1] + (Date.parse('2026-07-02') - ganttProgressData[1]) * 0.5) {
  throw new Error('gantt 未展示 progress 通道');
}

/** 未知版本和类型必须安全降级。 */
const invalidVersion = fixture('bar');
invalidVersion.schemaVersion = '2.0';
if (window.ChartAdapter.render(createContainer(), invalidVersion)) {
  throw new Error('未知协议版本未被拒绝');
}
const invalidType = fixture('bar');
invalidType.type = 'custom-script';
if (window.ChartAdapter.render(createContainer(), invalidType)) {
  throw new Error('未知图表类型未被拒绝');
}

/** setOption 失败时必须释放已经初始化但尚未登记的临时实例。 */
const originalInit = window.echarts.init;
let failedRenderDisposeCount = 0;
window.echarts.init = function() {
  return {
    setOption() { throw new Error('模拟图表渲染失败'); },
    dispose() { failedRenderDisposeCount += 1; },
    resize() {}
  };
};
if (window.ChartAdapter.render(createContainer(), fixture('bar'))) {
  throw new Error('setOption 异常未安全降级');
}
window.echarts.init = originalInit;
if (failedRenderDisposeCount !== 1) {
  throw new Error('setOption 异常后未释放临时图表实例');
}

/** 跨分片 CRLF 必须只生成一个换行，避免类型行与数据行被拆成两个事件。 */
const appSource = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
const normalizeStart = appSource.indexOf('function normalizeSSEChunk');
const normalizeEnd = appSource.indexOf('\n}\n', normalizeStart) + 2;
if (normalizeStart < 0 || normalizeEnd < 2) {
  throw new Error('未找到 SSE 换行规范化函数');
}
vm.runInThisContext(appSource.substring(normalizeStart, normalizeEnd), { filename: 'app.js#normalizeSSEChunk' });
const sseState = { pendingCarriageReturn: false };
let normalizedSse = '';
['event: delta\r', '\ndata: {"text":"A"}\r', '\n\r', '\n'].forEach(chunk => {
  normalizedSse += normalizeSSEChunk(chunk, sseState, false);
});
normalizedSse += normalizeSSEChunk('', sseState, true);
if (normalizedSse !== 'event: delta\ndata: {"text":"A"}\n\n') {
  throw new Error('跨分片 CRLF 被错误规范化');
}

/** 确认每个合法 fixture 都产生了 option。 */
if (options.length !== types.length + 10) {
	throw new Error('合法 fixture option 数量不正确');
}

process.stdout.write('chart-adapter fixtures passed: ' + options.length);
