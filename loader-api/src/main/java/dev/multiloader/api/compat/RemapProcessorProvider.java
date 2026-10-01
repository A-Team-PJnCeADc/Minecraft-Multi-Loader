package dev.multiloader.api.compat;

import dev.multiloader.api.transform.ClassProcessor;

/**
 * 前置处理器提供方(管道 {@code insertFirst} 语义的正式入口,本轮只定义接口,无实现).
 * <p>用途:旧版兼容层需要把"重映射"插到 Mixin **之前**执行.
 * 核心层不定义"重映射"这个阶段(那样就污染了),而是提供这个通用接口:
 * 实现方返回一个 {@link ClassProcessor},由它自己声明
 * {@code ProcessPhase},其 order 落在
 * {@link dev.multiloader.api.transform.ProcessPhases#PRE_PIPELINE_BASE} 之后,
 * 从而自然排到 {@code ProcessPhases.MIXIN} 之前.
 * <p>这样核心层对"前面插了个什么东西"完全无知,
 * 只承诺"order 更小的先跑"这一条排序规则.
*/
public interface RemapProcessorProvider {

    String name();

/** 是否处理这个目标. */
    boolean supports(LegacyTarget target);

/**
     * 创建要插入管道最前端的处理器.
     * @param loaderLayer 加载器层类加载器(实现类的依赖由它解析)
*/
    ClassProcessor createProcessor(ClassLoader loaderLayer);
}
