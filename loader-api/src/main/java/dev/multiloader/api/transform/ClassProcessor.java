package dev.multiloader.api.transform;

/**
 * 字节码处理器.管道按 (phase.order ->priority ->注册顺序) 稳定排序后依次执行.
 * <p>实现通过 {@code META-INF/services} 注册,或由 {@code MultiLoaderExtension}
 * 在 {@code onLoaderInit} 中登记.核心层不硬编码任何处理器.
*/
public interface ClassProcessor {

/** 诊断用名字,必须唯一且稳定. */
    String name();

/** 所处阶段.决定在管道中的大致位置. */
    ProcessPhase phase();

/** 同阶段内的先后.越小越先执行. */
    int priority();

/**
     * 是否处理这个类.
     * <p>实现应尽快返回:本方法对每个被加载的类都会被调用.
     * 拿不准时可返回 true,把精确判断留给 {@link #process}.
*/
    boolean handles(String className, byte[] input);

/**
     * 执行转换.
     * @return 转换后的字节码;返回 {@code ctx.input()} 表示不做修改
     * @throws TransformException 转换失败.抛出即视为致命,不允许静默跳过
*/
    byte[] process(ClassContext ctx) throws TransformException;
}
