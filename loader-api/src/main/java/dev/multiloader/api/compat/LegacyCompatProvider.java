package dev.multiloader.api.compat;

import dev.multiloader.api.service.ILoaderContext;

/**
 * 旧版兼容层的总入口(本轮只定义接口,无实现).
 * <p>契约:
 * <ul>
 *   <li>实现方通过 {@code META-INF/services} 注册</li>
 *   <li>核心层用 {@code ServiceRegistry.loadOptional(...)} 获取;
 *       拿不到就直接跳过,不报错,不打警告</li>
 *   <li>本接口及其参数中**不得出现任何映射类型**
 *       (MappingTable / NameSpaceGraph / MappingConverter 等),
 *       否则核心层就被迫在编译期依赖映射概念,INV-1 直接破防</li>
 * </ul>
*/
public interface LegacyCompatProvider {

    String name();

/** 是否处理这个目标.核心层据此决定要不要激活兼容层. */
    boolean supports(LegacyTarget target);

/**
     * 激活兼容层.
     * <p>实现应在此处加载映射表,注册自己的 {@link dev.multiloader.api.transform.ClassProcessor}
     * 与额外的 {@code MultiLoaderExtension}.
*/
    void activate(ILoaderContext ctx);
}
