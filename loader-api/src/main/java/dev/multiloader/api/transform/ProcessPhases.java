package dev.multiloader.api.transform;

/**
 * 核心层已知的转换阶段常量.
 * <p>核心层只定义"自己实现的"三个阶段.任何 order 小于
 * {@link #PRE_PIPELINE_BASE} 的阶段都被视为"前置阶段",
 * 会排在 Mixin 之前执行 这正是给旧版兼容层预留的
 * {@code insertFirst} 能力,核心层不需要知道那个阶段叫什么,做什么.
*/
public final class ProcessPhases {

    private ProcessPhases() {
    }

/**
     * 前置阶段的 order 基准.
     * <p>兼容层应使用 {@code PRE_PIPELINE_BASE} 加上自己的偏移,
     * 从而保证一定早于 {@link #MIXIN}.
*/
    public static final int PRE_PIPELINE_BASE = Integer.MIN_VALUE / 2;

/** Mixin 注入.必须是管道里的第一优先级(在此之前只允许前置阶段). */
    public static final ProcessPhase MIXIN = new ProcessPhase("mixin", 0);

/** AccessTransformer / AccessWidener 应用. */
    public static final ProcessPhase ACCESS_TRANSFORMER = new ProcessPhase("access-transformer", 100);

/** 其他所有核心与桥接层处理器的默认阶段. */
    public static final ProcessPhase DEFAULT = new ProcessPhase("default", 1000);
}
