/**
 * 通用 ChartSpec 到 Apache ECharts option 的安全适配器。
 * 仅接受固定协议、固定图表类型和声明式数据，不执行服务端函数、HTML 或 URL。
 */
(function(global) {
  'use strict';

  /** 当前支持的图表类型白名单。 */
  var SUPPORTED_TYPES = new Set([
    'donut', 'sunburst', 'bar', 'waterfall', 'bullet', 'area', 'step', 'radar',
    'scatter', 'bubble', 'histogram', 'boxplot', 'heatmap', 'sankey', 'treemap',
    'gantt', 'funnel', 'word-cloud', 'gauge', 'liquid-fill', 'parallel', 'line', 'pie'
  ]);

  /** 每个图表容器当前绑定的 ECharts 实例。 */
  var instances = new Map();

  /**
   * 将任意后端文本限制为安全展示字符串。
   *
   * @param {*} value 待展示值
   * @param {number} maxLength 最大字符数
   * @returns {string} 截断后的安全文本
   */
  function safeText(value, maxLength) {
    var text = value == null ? '' : String(value);
    return text.substring(0, maxLength || 120);
  }

  /**
   * 从 CSS 主题变量读取颜色并提供安全默认值。
   *
   * @param {string} name CSS 主题变量名
   * @param {string} fallback 主题变量缺失时的默认颜色
   * @returns {string} 可用于图表的颜色值
   */
  function themeColor(name, fallback) {
    if (!global.document || !global.getComputedStyle) return fallback;
    var value = global.getComputedStyle(global.document.documentElement)
      .getPropertyValue(name).trim();
    return value || fallback;
  }

  /**
   * 校验协议版本、类型和数据规模。
   *
   * @param {Object} spec 图表协议对象
   * @returns {void}
   * @throws {Error} 协议版本、图表类型或数据规模不合法时抛出
   */
  function validateSpec(spec) {
    if (!spec || spec.schemaVersion !== '1.0') {
      throw new Error('不支持的图表协议版本');
    }
    if (!SUPPORTED_TYPES.has(spec.type)) {
      throw new Error('不支持的图表类型');
    }
    if (!spec.dataset || !Array.isArray(spec.dataset.dimensions) ||
        !Array.isArray(spec.dataset.rows)) {
      throw new Error('图表数据集不完整');
    }
    if (spec.dataset.dimensions.length > 32 || spec.dataset.rows.length > 50) {
      throw new Error('图表数据规模超过限制');
    }
    if (!spec.encoding || typeof spec.encoding !== 'object') {
      throw new Error('图表语义编码不完整');
    }
  }

  /**
   * 获取语义通道绑定的字段键列表。
   *
   * @param {Object} spec 图表协议对象
   * @param {string} channel 语义通道名称
   * @returns {Array<string>} 受长度限制的字段键列表
   */
  function keys(spec, channel) {
    var value = spec.encoding[channel];
    return Array.isArray(value) ? value.slice(0, 32) : [];
  }

  /**
   * 获取语义通道的首个字段键。
   *
   * @param {Object} spec 图表协议对象
   * @param {string} channel 语义通道名称
   * @returns {string|undefined} 首个字段键
   */
  function key(spec, channel) {
    return keys(spec, channel)[0];
  }

  /**
   * 获取单行指定语义通道的值。
   *
   * @param {Object} spec 图表协议对象
   * @param {Object} row 数据行
   * @param {string} channel 语义通道名称
   * @returns {*} 语义通道对应的数据值
   */
  function value(spec, row, channel) {
    return row[key(spec, channel)];
  }

  /**
   * 构建所有图表共用的安全 option。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} 基础 ECharts option
   */
  function baseOption(spec) {
    return {
      animationDuration: 300,
      color: [
        themeColor('--primary', '#4f46e5'),
        themeColor('--success', '#10b981'),
        themeColor('--warning', '#f59e0b'),
        themeColor('--error', '#ef4444'),
        '#06b6d4', '#8b5cf6', '#ec4899'
      ],
      title: {
        text: safeText(spec.title, 120),
        subtext: safeText(spec.subtitle, 160),
        left: 'center',
        top: 0,
        textStyle: { color: themeColor('--text', '#111827'), fontSize: 15 }
      },
      tooltip: {
        trigger: 'item',
        renderMode: 'richText',
        confine: true
      },
      legend: {
        type: 'scroll',
        top: 54,
        textStyle: { color: themeColor('--text-secondary', '#4b5563') }
      }
    };
  }

  /**
   * 构建饼图或环形图。
   *
   * @param {Object} spec 图表协议对象
   * @param {boolean} donut 是否使用环形图样式
   * @returns {Object} ECharts option
   */
  function pieOption(spec, donut) {
    var option = baseOption(spec);
    option.series = [{
      type: 'pie',
      radius: donut ? ['42%', '68%'] : '68%',
      center: ['50%', '57%'],
      data: spec.dataset.rows.map(function(row) {
        return { name: safeText(value(spec, row, 'name'), 120), value: value(spec, row, 'value') };
      }),
      label: { show: !spec.options || spec.options.showLabel !== false }
    }];
    return option;
  }

  /**
   * 构建条形、折线、面积和阶梯图的直角坐标 option。
   *
   * @param {Object} spec 图表协议对象
   * @param {string} seriesType 系列类型
   * @returns {Object} ECharts option
   */
  function cartesianOption(spec, seriesType) {
    var option = baseOption(spec);
    var categoryChannel = seriesType === 'bar' ? 'category' : 'x';
    var valueChannel = seriesType === 'bar' ? 'value' : 'y';
    var categoryKey = key(spec, categoryChannel);
    var valueKeys = keys(spec, valueChannel);
    var seriesKey = key(spec, 'series');
    var seriesGroups = groupRowsBySeries(spec);
    // 多系列按每个类目的最大出现次数扩展坐标槽；单系列直接保留逐行业务数据。
    var categories = seriesKey
      ? expandSeriesCategories(spec.dataset.rows, seriesGroups, categoryKey)
      : spec.dataset.rows.map(function(row) { return row[categoryKey]; });
    var horizontal = seriesType === 'bar' && spec.options && spec.options.orientation === 'horizontal';
    var categoryAxis = { type: 'category', data: categories, axisLabel: { hideOverlap: true } };
    var valueAxis = { type: 'value', scale: true };
    option.grid = { top: 90, left: 55, right: 30, bottom: 48, containLabel: true };
    option.xAxis = horizontal ? valueAxis : categoryAxis;
    option.yAxis = horizontal ? categoryAxis : valueAxis;
    // series 通道存在时按系列拆分，并按统一类别轴补齐缺失点。
    option.series = seriesGroups.flatMap(function(group) {
      return valueKeys.map(function(valueKey) {
        var valueLabel = dimensionLabel(spec, valueKey);
        var series = {
          name: seriesKey
            ? safeText(valueKeys.length > 1 ? group.name + ' / ' + valueLabel : group.name, 120)
            : valueLabel,
          type: seriesType === 'bar' ? 'bar' : 'line',
          // 多系列按类别及出现顺序补齐缺失点，单系列直接逐行映射。
          data: seriesKey
            ? alignSeriesValues(categories, group.rows, categoryKey, valueKey)
            : group.rows.map(function(row) { return row[valueKey]; }),
          stack: spec.options && spec.options.stacked ? 'total' : undefined
        };
        if (seriesType === 'area') series.areaStyle = {};
        if (seriesType === 'step') series.step = (spec.options && spec.options.step) || 'middle';
        if (seriesType === 'line' || seriesType === 'area') {
          series.smooth = !!(spec.options && spec.options.smooth);
        }
        return series;
      });
    });
    return option;
  }

  /**
   * 构建漏斗图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function funnelOption(spec) {
    var option = baseOption(spec);
    option.series = [{
      type: 'funnel',
      top: 70,
      bottom: 20,
      sort: spec.options && spec.options.sort === 'asc' ? 'ascending' : 'descending',
      data: spec.dataset.rows.map(function(row) {
        return { name: safeText(value(spec, row, 'name'), 120), value: value(spec, row, 'value') };
      })
    }];
    return option;
  }

  /**
   * 构建瀑布图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function waterfallOption(spec) {
    var option = baseOption(spec);
    var rows = spec.dataset.rows;
    option.grid = { top: 90, left: 55, right: 30, bottom: 48, containLabel: true };
    option.xAxis = { type: 'category', data: rows.map(function(row) { return row.category; }) };
    option.yAxis = { type: 'value' };
    option.series = [
      { type: 'bar', stack: 'waterfall', data: rows.map(function(row) { return row.base; }),
        itemStyle: { color: 'transparent' }, silent: true },
      { name: '增加', type: 'bar', stack: 'waterfall',
        data: rows.map(function(row) { return row.increase; }) },
      { name: '减少', type: 'bar', stack: 'waterfall',
        data: rows.map(function(row) { return row.decrease; }) }
    ];
    return option;
  }

  /**
   * 构建子弹图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function bulletOption(spec) {
    var option = baseOption(spec);
    var rows = spec.dataset.rows;
    option.grid = { top: 90, left: 70, right: 35, bottom: 42, containLabel: true };
    option.xAxis = { type: 'value' };
    option.yAxis = { type: 'category', data: rows.map(function(row) {
      return value(spec, row, 'category');
    }) };
    option.series = [
      { name: '范围', type: 'bar', barGap: '-100%', silent: true,
        itemStyle: { color: themeColor('--border', '#e5e7eb') },
        data: rows.map(function(row) { return value(spec, row, 'range'); }) },
      { name: '实际', type: 'bar',
        data: rows.map(function(row) { return value(spec, row, 'actual'); }) },
      { name: '目标', type: 'scatter', symbol: 'rect', symbolSize: [4, 22],
        data: rows.map(function(row, index) {
          return [value(spec, row, 'target'), index];
        }) }
    ];
    return option;
  }

  /**
   * 构建雷达图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function radarOption(spec) {
    var option = baseOption(spec);
    var indicatorKeys = keys(spec, 'indicator');
    var maximumKeys = keys(spec, 'indicatorMax');
    option.radar = {
      center: ['50%', '58%'],
      radius: '62%',
      indicator: indicatorKeys.map(function(field, index) {
        var maximum = maximumKeys[index] && spec.dataset.rows[0]
          ? spec.dataset.rows[0][maximumKeys[index]] : null;
        return { name: dimensionLabel(spec, field), max: maximum || 1 };
      })
    };
    option.series = [{
      type: 'radar',
      data: spec.dataset.rows.map(function(row) {
        return {
          name: safeText(value(spec, row, 'name'), 120),
          value: indicatorKeys.map(function(field) { return row[field]; })
        };
      })
    }];
    return option;
  }

  /**
   * 构建散点图或气泡图。
   *
   * @param {Object} spec 图表协议对象
   * @param {boolean} bubble 是否按气泡大小渲染
   * @returns {Object} ECharts option
   */
  function scatterOption(spec, bubble) {
    var option = baseOption(spec);
    option.grid = { top: 90, left: 55, right: 40, bottom: 48, containLabel: true };
    option.xAxis = { type: 'value', scale: true };
    option.yAxis = { type: 'value', scale: true };
    // 散点图和气泡图按可选 series 通道分别生成图例系列。
    option.series = groupRowsBySeries(spec).map(function(group) {
      return {
        name: group.name || undefined,
        type: 'scatter',
        data: group.rows.map(function(row) {
          var point = [value(spec, row, 'x'), value(spec, row, 'y')];
          if (bubble) point.push(value(spec, row, 'visualSize'));
          return point;
        }),
        symbolSize: bubble ? function(point) { return point[2] || 20; } : 10
      };
    });
    return option;
  }

  /**
   * 构建直方图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function histogramOption(spec) {
    var option = baseOption(spec);
    option.grid = { top: 90, left: 55, right: 30, bottom: 48, containLabel: true };
    option.xAxis = { type: 'category', data: spec.dataset.rows.map(function(row) {
      return safeText(row.binLabel, 80);
    }), axisLabel: { rotate: 25 } };
    option.yAxis = { type: 'value', minInterval: 1 };
    option.series = [{ type: 'bar', name: '数量', data: spec.dataset.rows.map(function(row) {
      return row.count;
    }) }];
    return option;
  }

  /**
   * 构建箱线图及异常点。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function boxplotOption(spec) {
    var option = baseOption(spec);
    var rows = spec.dataset.rows;
    option.grid = { top: 90, left: 55, right: 30, bottom: 48, containLabel: true };
    option.xAxis = { type: 'category', data: rows.map(function(row) { return row.category; }) };
    option.yAxis = { type: 'value', scale: true };
    option.series = [
      { type: 'boxplot', data: rows.map(function(row) {
        return [row.min, row.q1, row.median, row.q3, row.max];
      }) },
      { type: 'scatter', name: '异常值', data: rows.flatMap(function(row, index) {
        return (row.outliers || []).map(function(outlier) { return [index, outlier]; });
      }) }
    ];
    return option;
  }

  /**
   * 构建热力图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   * @throws {Error} 热力图没有有效数值时抛出
   */
  function heatmapOption(spec) {
    var option = baseOption(spec);
    var xValues = unique(spec.dataset.rows.map(function(row) { return value(spec, row, 'x'); }));
    var yValues = unique(spec.dataset.rows.map(function(row) { return value(spec, row, 'y'); }));
    // 计算色阶时跳过空值，避免 Number(null) 把缺失业务数据伪造成零。
    var heatValues = spec.dataset.rows
      .map(function(row) { return value(spec, row, 'value'); })
      .filter(function(item) { return item !== null && item !== undefined; })
      .map(function(item) { return Number(item); });
    if (heatValues.length === 0 || heatValues.some(function(item) { return !Number.isFinite(item); })) {
      throw new Error('热力图缺少有效数值');
    }
    option.grid = { top: 90, left: 55, right: 65, bottom: 48, containLabel: true };
    option.xAxis = { type: 'category', data: xValues };
    option.yAxis = { type: 'category', data: yValues };
    option.visualMap = {
      min: Math.min.apply(null, heatValues),
      max: Math.max.apply(null, heatValues),
      calculable: true,
      right: 0,
      top: 80
    };
    option.series = [{ type: 'heatmap', data: spec.dataset.rows.map(function(row) {
      return [xValues.indexOf(value(spec, row, 'x')),
        yValues.indexOf(value(spec, row, 'y')), value(spec, row, 'value')];
    }) }];
    return option;
  }

  /**
   * 构建旭日图或矩形树图。
   *
   * @param {Object} spec 图表协议对象
   * @param {string} type 层级图类型
   * @returns {Object} ECharts option
   */
  function hierarchyOption(spec, type) {
    var option = baseOption(spec);
    var tree = buildTree(spec);
    option.series = [{
      type: type,
      top: 84,
      data: tree,
      radius: type === 'sunburst' ? ['12%', '72%'] : undefined,
      label: { show: !spec.options || spec.options.showLabel !== false }
    }];
    return option;
  }

  /**
   * 将扁平节点行转换为 ECharts 层级树。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Array<Object>} ECharts 层级树根节点
   */
  function buildTree(spec) {
    var idField = key(spec, 'id');
    var parentField = key(spec, 'parentId');
    var nameField = key(spec, 'name');
    var valueField = key(spec, 'value');
    var nodeMap = new Map();
    spec.dataset.rows.forEach(function(row) {
      nodeMap.set(String(row[idField]), {
        name: safeText(row[nameField], 120),
        value: row[valueField],
        children: []
      });
    });
    var roots = [];
    spec.dataset.rows.forEach(function(row) {
      var node = nodeMap.get(String(row[idField]));
      var parentId = row[parentField] == null ? '' : String(row[parentField]);
      if (parentId && nodeMap.has(parentId)) nodeMap.get(parentId).children.push(node);
      else roots.push(node);
    });
    return roots;
  }

  /**
   * 构建桑基图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function sankeyOption(spec) {
    var option = baseOption(spec);
    var names = [];
    spec.dataset.rows.forEach(function(row) {
      var source = String(value(spec, row, 'source'));
      var target = String(value(spec, row, 'target'));
      names.push(source, target);
    });
    // 完整节点值用于稳定关联，截断只作用于最终展示名。
    var nodeIds = new Map();
    unique(names).forEach(function(name, index) {
      nodeIds.set(name, 'sankey-node-' + index);
    });
    var links = spec.dataset.rows.map(function(row) {
      var source = String(value(spec, row, 'source'));
      var target = String(value(spec, row, 'target'));
      return {
        source: nodeIds.get(source),
        target: nodeIds.get(target),
        value: value(spec, row, 'value')
      };
    });
    option.series = [{
      type: 'sankey',
      top: 86,
      data: unique(names).map(function(name) {
        return { id: nodeIds.get(name), name: safeText(name, 120) };
      }),
      links: links,
      emphasis: { focus: 'adjacency' }
    }];
    return option;
  }

  /**
   * 按时间横轴和任务类别纵轴绘制固定的甘特水平范围条。
   *
   * @param {Object} params ECharts custom series 渲染参数
   * @param {Object} api ECharts custom series 数据与坐标 API
   * @returns {Object|null} 裁剪后的水平矩形图元
   */
  function renderGanttItem(params, api) {
    var categoryIndex = api.value(0);
    var start = api.coord([api.value(1), categoryIndex]);
    var end = api.coord([api.value(2), categoryIndex]);
    var height = Math.max(api.size([0, 1])[1] * 0.48, 2);
    var shape = global.echarts.graphic.clipRectByRect({
      x: start[0],
      y: start[1] - height / 2,
      width: Math.max(end[0] - start[0], 1),
      height: height
    }, {
      x: params.coordSys.x,
      y: params.coordSys.y,
      width: params.coordSys.width,
      height: params.coordSys.height
    });
    return shape ? {
      type: 'rect',
      shape: shape,
      style: { fill: api.visual('color') }
    } : null;
  }

  /**
   * 构建甘特图，使用应用内固定的水平时间范围渲染函数。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function ganttOption(spec) {
    var option = baseOption(spec);
    var categories = spec.dataset.rows.map(function(row) { return value(spec, row, 'category'); });
    var progressField = key(spec, 'progress');
    option.grid = { top: 90, left: 75, right: 35, bottom: 48, containLabel: true };
    option.xAxis = { type: 'time' };
    option.yAxis = { type: 'category', data: categories };
    option.series = [{
      type: 'custom',
      renderItem: renderGanttItem,
      encode: { x: [1, 2], y: 0, tooltip: [1, 2] },
      data: spec.dataset.rows.map(function(row, index) {
        return [
          index,
          Date.parse(value(spec, row, 'start')),
          Date.parse(value(spec, row, 'end'))
        ];
      })
    }];
    if (progressField) {
      // 可选进度以前景范围条叠加，完整任务范围仍由首个系列保留。
      option.series.push({
        name: '已完成',
        type: 'custom',
        renderItem: renderGanttItem,
        encode: { x: [1, 2], y: 0, tooltip: [1, 2, 3] },
        itemStyle: { color: themeColor('--success', '#10b981') },
        z: 3,
        data: spec.dataset.rows.map(function(row, index) {
          var start = Date.parse(value(spec, row, 'start'));
          var end = Date.parse(value(spec, row, 'end'));
          var progress = Number(row[progressField]);
          return [index, start, start + (end - start) * progress, progress];
        })
      });
    }
    return option;
  }

  /**
   * 构建词云图，使用官方 wordCloud custom series。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function wordCloudOption(spec) {
    var option = baseOption(spec);
    option.series = [{
      type: 'custom',
      renderItem: 'wordCloud',
      coordinateSystem: 'none',
      itemPayload: {
        left: 10,
        top: 80,
        right: 10,
        bottom: 10,
        gridSize: 8,
        sizeRange: [12, 42],
        rotationRange: [0, 0],
        shape: 'circle',
        shrinkToFit: true,
        drawOutOfBound: false
      },
      data: spec.dataset.rows.map(function(row) {
        return [safeText(value(spec, row, 'name'), 80), value(spec, row, 'value')];
      })
    }];
    return option;
  }

  /**
   * 构建仪表盘。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function gaugeOption(spec) {
    var option = baseOption(spec);
    var row = spec.dataset.rows[0] || {};
    option.series = [{
      type: 'gauge',
      min: spec.options && spec.options.min != null ? spec.options.min : 0,
      max: spec.options && spec.options.max != null ? spec.options.max : 100,
      data: [{ name: safeText(value(spec, row, 'name'), 120), value: value(spec, row, 'value') }],
      detail: { valueAnimation: true }
    }];
    return option;
  }

  /**
   * 构建水位图，使用官方 liquidFill custom series。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function liquidFillOption(spec) {
    var option = baseOption(spec);
    option.series = [{
      type: 'custom',
      renderItem: 'liquidFill',
      coordinateSystem: 'none',
      colorBy: 'item',
      itemPayload: {
        center: ['50%', '58%'],
        radius: '66%',
        outline: { show: false },
        backgroundStyle: { color: themeColor('--border', '#e5e7eb') },
        itemStyle: { opacity: 0.82 },
        labelInsideColor: '#ffffff',
        waveAnimation: true
      },
      data: spec.dataset.rows.map(function(row) { return value(spec, row, 'normalized'); })
    }];
    return option;
  }

  /**
   * 构建平行坐标图。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   */
  function parallelOption(spec) {
    var option = baseOption(spec);
    var parallelKeys = keys(spec, 'parallel');
    option.parallelAxis = parallelKeys.map(function(field, index) {
      return { dim: index, name: dimensionLabel(spec, field), type: 'value' };
    });
    option.parallel = { top: 90, left: 55, right: 35, bottom: 35 };
    option.series = [{
      type: 'parallel',
      data: spec.dataset.rows.map(function(row) {
        return {
          name: safeText(value(spec, row, 'name'), 120),
          value: parallelKeys.map(function(field) { return row[field]; })
        };
      })
    }];
    return option;
  }

  /**
   * 根据字段键读取安全维度名称。
   *
   * @param {Object} spec 图表协议对象
   * @param {string} field 字段键
   * @returns {string} 安全维度名称
   */
  function dimensionLabel(spec, field) {
    var dimension = spec.dataset.dimensions.find(function(item) { return item.key === field; });
    return safeText(dimension ? dimension.label : field, 120);
  }

  /**
   * 返回去重且保持原顺序的数组。
   *
   * @param {Array<*>} values 原始值数组
   * @returns {Array<*>} 去重后的数组
   */
  function unique(values) {
    return Array.from(new Set(values));
  }

  /**
   * 按可选 series 通道分组数据行，并保持首次出现顺序。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Array<Object>} 系列名称和数据行分组
   */
  function groupRowsBySeries(spec) {
    var seriesField = key(spec, 'series');
    if (!seriesField) {
      return [{ name: '', rows: spec.dataset.rows }];
    }
    var groupedRows = new Map();
    spec.dataset.rows.forEach(function(row) {
      // 完整系列值用于分组，避免展示截断后不同业务系列被合并。
      var seriesIdentity = row[seriesField] == null ? '' : String(row[seriesField]);
      if (!groupedRows.has(seriesIdentity)) groupedRows.set(seriesIdentity, []);
      groupedRows.get(seriesIdentity).push(row);
    });
    return Array.from(groupedRows, function(entry) {
      return { name: safeText(entry[0], 120), rows: entry[1] };
    });
  }

  /**
   * 按各系列中同类目的最大出现次数扩展统一坐标轴。
   *
   * @param {Array<Object>} rows 全部数据行
   * @param {Array<Object>} seriesGroups 系列分组
   * @param {string} categoryField 类目字段
   * @returns {Array<*>} 可保留重复类目的统一坐标轴
   */
  function expandSeriesCategories(rows, seriesGroups, categoryField) {
    return unique(rows.map(function(row) { return row[categoryField]; }))
      .flatMap(function(category) {
        var maximum = seriesGroups.reduce(function(current, group) {
          var count = group.rows.filter(function(row) {
            return row[categoryField] === category;
          }).length;
          return Math.max(current, count);
        }, 0);
        return Array.from({ length: maximum }, function() { return category; });
      });
  }

  /**
   * 将单个系列按类目及第几次出现映射到统一坐标轴。
   *
   * @param {Array<*>} categories 统一坐标轴
   * @param {Array<Object>} rows 当前系列数据行
   * @param {string} categoryField 类目字段
   * @param {string} valueField 数值字段
   * @returns {Array<*>} 对齐后的系列数据
   */
  function alignSeriesValues(categories, rows, categoryField, valueField) {
    var usedOccurrences = new Map();
    return categories.map(function(category) {
      var occurrence = usedOccurrences.get(category) || 0;
      usedOccurrences.set(category, occurrence + 1);
      var matches = rows.filter(function(row) {
        return row[categoryField] === category;
      });
      return matches[occurrence] ? matches[occurrence][valueField] : null;
    });
  }

  /**
   * 根据固定类型白名单构建 ECharts option。
   *
   * @param {Object} spec 图表协议对象
   * @returns {Object} ECharts option
   * @throws {Error} 协议或图表类型不受支持时抛出
   */
  function buildOption(spec) {
    validateSpec(spec);
    switch (spec.type) {
      case 'pie': return pieOption(spec, false);
      case 'donut': return pieOption(spec, true);
      case 'bar': return cartesianOption(spec, 'bar');
      case 'line': return cartesianOption(spec, 'line');
      case 'area': return cartesianOption(spec, 'area');
      case 'step': return cartesianOption(spec, 'step');
      case 'funnel': return funnelOption(spec);
      case 'waterfall': return waterfallOption(spec);
      case 'bullet': return bulletOption(spec);
      case 'radar': return radarOption(spec);
      case 'scatter': return scatterOption(spec, false);
      case 'bubble': return scatterOption(spec, true);
      case 'histogram': return histogramOption(spec);
      case 'boxplot': return boxplotOption(spec);
      case 'heatmap': return heatmapOption(spec);
      case 'sunburst': return hierarchyOption(spec, 'sunburst');
      case 'treemap': return hierarchyOption(spec, 'treemap');
      case 'sankey': return sankeyOption(spec);
      case 'gantt': return ganttOption(spec);
      case 'word-cloud': return wordCloudOption(spec);
      case 'gauge': return gaugeOption(spec);
      case 'liquid-fill': return liquidFillOption(spec);
      case 'parallel': return parallelOption(spec);
      default: throw new Error('不支持的图表类型');
    }
  }

  /**
   * 在容器中显示不包含后端 HTML 的降级信息。
   *
   * @param {HTMLElement} container 图表容器
   * @param {string} message 降级提示
   * @returns {void}
   */
  function showError(container, message) {
    container.classList.add('chart-container-error');
    container.textContent = safeText(message || '图表暂时无法展示', 160);
  }

  /**
   * 创建或替换指定容器中的图表实例。
   *
   * @param {HTMLElement} container 图表容器
   * @param {Object} spec 图表协议对象
   * @returns {boolean} 是否渲染成功
   */
  function render(container, spec) {
    if (!container) return false;
    disposeWithin(container);
    container.classList.remove('chart-container-error');
    if (!global.echarts || typeof global.echarts.init !== 'function') {
      showError(container, '图表组件未加载');
      return false;
    }
    var chart = null;
    try {
      chart = global.echarts.init(container);
      chart.setOption(buildOption(spec), true);
      instances.set(container, chart);
      return true;
    } catch (error) {
      // 初始化后任一步失败都立即释放临时实例，避免残留 Canvas 和监听器。
      if (chart && typeof chart.dispose === 'function') chart.dispose();
      showError(container, error && error.message ? error.message : '图表暂时无法展示');
      return false;
    }
  }

  /**
   * 释放根节点自身及其后代中的图表实例。
   *
   * @param {HTMLElement} root 图表容器或其祖先节点
   * @returns {void}
   */
  function disposeWithin(root) {
    instances.forEach(function(chart, container) {
      if (container === root || (root && typeof root.contains === 'function' && root.contains(container))) {
        chart.dispose();
        instances.delete(container);
      }
    });
  }

  /**
   * 调整根节点自身及其后代中的图表尺寸。
   *
   * @param {HTMLElement} root 图表容器或其祖先节点
   * @returns {void}
   */
  function resizeWithin(root) {
    instances.forEach(function(chart, container) {
      if (!root || container === root || (typeof root.contains === 'function' && root.contains(container))) {
        chart.resize();
      }
    });
  }

  /** 仅暴露渲染、释放和尺寸调整三个稳定 API。 */
  global.ChartAdapter = {
    render: render,
    disposeWithin: disposeWithin,
    resizeWithin: resizeWithin
  };
})(window);
