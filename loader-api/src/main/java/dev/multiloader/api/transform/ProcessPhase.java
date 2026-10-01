package dev.multiloader.api.transform;

import java.util.Objects;

/**
 * 转换管道中的一个阶段.按 {@link #order} 升序执行.
 * <p>刻意做成 record 而不是 enum:枚举是封闭集合,外部兼容层无法往里加值.
 * 旧版兼容层需要声明一个排在 Mixin 之前的阶段(重映射必须先于注入),
 * 它必须能定义自己的 {@code ProcessPhase},否则核心层就得认识"重映射"这个概念,
 * 而这是被明确禁止的.
 * <p>核心层只定义 {@link ProcessPhases} 里的几个常量,不解释其他阶段的语义.
*/
public record ProcessPhase(String name, int order) implements Comparable<ProcessPhase> {

    public ProcessPhase {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("phase name must not be blank");
        }
    }

    @Override
    public int compareTo(ProcessPhase other) {
        int byOrder = Integer.compare(order, other.order);
        // order 相同时用 name 兜底,保证排序结果确定(确定性对 Mixin 冲突排查是刚需)
        return byOrder != 0 ? byOrder : name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name + "(" + order + ")";
    }
}
