package dev.multiloader.api.metadata;

/**
 * 当前**进程**运行在哪一侧.
 * <p><b>与 {@link Environment} 的区别(重要,勿混用)</b>:
 * <pre>
 *   Environment  这个 mod **声明支持**哪些侧别(可以同时是 CLIENT 与 SERVER)
 *   GameSide     这个**进程**现在是哪一侧(永远是二选一)
 * </pre>
 * 取值域重叠但含义不同,所以刻意分成两个类型.
 * <p><b>为什么在这里单独声明,而不复用 runtime-common 的 {@code Side}</b>:
 * loader-api 有 INV-1"编译类路径零依赖"的硬约束(验收命令必须输出
 * {@code No dependencies}),引用 runtime-common 会破坏它.
 * 两者之间的映射只在 {@code LoaderContextImpl} **一处**发生  边界清晰,
 * 不会扩散成"核心层到处出现 runtime-common 类型".
 * <p>只有两个取值是刻意的:出现第三个(例如逻辑侧的集成服务器)时,
 * 所有 {@code switch} 会编译失败,从而强制逐一复核 
 * 这比"悄悄多出一个值,各处按旧假设继续跑"好得多.
*/
public enum GameSide {
    CLIENT,
    SERVER;
}
