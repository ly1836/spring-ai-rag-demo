package com.example.rag.chat.chart.protocol;

import java.util.Set;

import com.example.rag.vo.ChartVO;

/**
 * 图表安全展示选项统一校验器，供规划与历史协议复用。
 */
public final class ChartOptionsValidator {

	/**
	 * 校验图表展示选项的枚举、范围和纯文本约束。
	 *
	 * @param options 图表展示选项
	 * @throws IllegalArgumentException 展示选项不符合安全白名单时抛出
	 */
	public static void validate(ChartVO.ChartOptions options) {
		if (options == null) {
			return;
		}
		if (options.orientation() != null
				&& !Set.of("horizontal", "vertical").contains(options.orientation())) {
			throw new IllegalArgumentException("图表方向不受支持");
		}
		if (options.step() != null && !Set.of("start", "middle", "end").contains(options.step())) {
			throw new IllegalArgumentException("阶梯方式不受支持");
		}
		if (options.sort() != null && !Set.of("asc", "desc").contains(options.sort())) {
			throw new IllegalArgumentException("展示排序方式不受支持");
		}
		if (options.binCount() != null && (options.binCount() < 5 || options.binCount() > 20)) {
			throw new IllegalArgumentException("直方图分箱数量必须为5到20");
		}
		if (options.unit() != null && !isSafeUnit(options.unit())) {
			throw new IllegalArgumentException("展示单位不合法");
		}
		if ((options.min() != null && !Double.isFinite(options.min()))
				|| (options.max() != null && !Double.isFinite(options.max()))) {
			throw new IllegalArgumentException("图表范围必须为有限数值");
		}
		if (options.min() != null && options.max() != null && options.min() >= options.max()) {
			throw new IllegalArgumentException("图表最小值必须小于最大值");
		}
	}

	/**
	 * 判断展示单位是否为安全短文本。
	 *
	 * @param unit 展示单位
	 * @return 是否安全
	 */
	private static boolean isSafeUnit(String unit) {
		if (unit.isBlank() || unit.length() > 20) {
			return false;
		}
		String lower = unit.toLowerCase();
		return !unit.contains("<") && !unit.contains(">")
			&& !lower.contains("javascript:") && !lower.contains("http://") && !lower.contains("https://");
	}

	/**
	 * 禁止实例化统一校验器。
	 */
	private ChartOptionsValidator() {
	}

}
