package dev.multiloader.mixin.platform;

import dev.multiloader.common.Log;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

/**
 * 把 Mixin 的日志接到我们自己的 {@link Log}.
 * <p><b>为什么这个类必不可少:</b>Mixin 默认的 {@code LoggerAdapterDefault}
 * 自称 {@code "Default Logger (No Logging)"}  它的每个日志方法都是**空实现**.
 * 于是 Mixin 内部所有警告与错误("mixin 目标方法找不到","注入点匹配失败",
 * "配置里的类不存在")全部被静默丢弃,外部只看到"mixins 就是没生效",
 * 没有任何线索.这类静默失效比崩溃难查一个数量级.
 * <p>只需实现四个方法:{@code LoggerAdapterAbstract} 已经把
 * debug/info/warn/error/trace 的各个重载都转发到 {@link #log(Level, String, Object...)}
 * 与 {@link #log(Level, String, Throwable)}.
 * <p>消息格式与 Mixin 一致({@code {}} 占位符),而我们 {@link Log} 用的
 * 正好也是同一套占位符语法,所以可以直接透传,不需要二次格式化.
*/
final class MultiLoaderLogger extends LoggerAdapterAbstract {

    MultiLoaderLogger(String name) {
        super(name);
    }

    @Override
    public String getType() {
        return "MultiLoader Logger";
    }

    @Override
    public void log(Level level, String message, Object... params) {
        switch (level) {
            case TRACE -> Log.trace(message, params);
            case DEBUG -> Log.debug(message, params);
            case INFO -> Log.info(message, params);
            case WARN -> Log.warn(message, params);
            case ERROR, FATAL -> Log.error(message, params);
        }
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        switch (level) {
            case TRACE -> Log.trace(message, throwable);
            case DEBUG -> Log.debug(message, throwable);
            case INFO -> Log.info(message, throwable);
            case WARN -> Log.warn(message, throwable);
            case ERROR, FATAL -> Log.error(message, throwable);
        }
    }

    @Override
    public void catching(Level level, Throwable throwable) {
        // Mixin 用它报告"我吞掉了一个异常"  必须可见,
        // 否则又是一次静默失效
        Log.warn("Mixin caught an exception at {}: {}", level, throwable);
    }

/**
     * Mixin 的 {@code ILogger} 把 {@code throwing(T)} 定义为抽象方法
     * (语义是"记下这个异常并把原对象还回去,便于调用方写 {@code throw logger.throwing(e)}").
     * 我们不额外记录  真正的抛出点上游会自己报  只把对象原样返回.
*/
    @Override
    public <T extends Throwable> T throwing(T throwable) {
        return throwable;
    }
}
