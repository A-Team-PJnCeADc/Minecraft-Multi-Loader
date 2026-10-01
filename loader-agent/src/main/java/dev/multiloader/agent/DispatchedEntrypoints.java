package dev.multiloader.agent;

import dev.multiloader.api.lifecycle.EntrypointRef;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已分发入口点的**实例缓存** -- 加载器分发时登记,查询时读取.
 * <p><b>存在的理由</b>:入口点的实例化公式(含"带参构造器需要服务注入"那条)
 * 只应存在于**分发路径**里.而 Fabric 那侧要求 mod 能**主动查询**入口点实例
 * ({@code FabricLoader.getEntrypoints(key, type)} 返回实例).若让桥接层自己实例化,
 * 同一段初始化代码会被执行两遍:
 * <pre>
 *   core 在 LifecyclePhase.INIT 造一个,mod 查询时桥接层再造一个
 *   两份实例,两次初始化 -- 只有依赖"初始化只发生一次"的 mod 才会发现,
 *   而那时归因会指向 mod 自己
 * </pre>
 * 所以实例只造一次(在分发时),查询从缓存取.
 * <p><b>{@code putIfAbsent} 而不是 {@code put}</b>:若同一 ref 被分发两次
 * (例如加载器重入),保留**第一个**实例.后来的覆盖会让先前拿到实例的 mod
 * 与后来的 mod 持有不同对象 -- 静默的双实例问题正是本缓存要消除的东西.
 * <p>用 {@link ConcurrentHashMap} 而非加锁 Map:查询可能发生在任意 mod 的线程上.
*/
final class DispatchedEntrypoints {

    private static final Map<EntrypointRef, Object> INSTANCES = new ConcurrentHashMap<>();

    private DispatchedEntrypoints() {
    }

/** 分发时登记实例.同一 ref 重复登记保留第一个. */
    static void record(EntrypointRef ref, Object instance) {
        if (ref == null || instance == null) {
            return;
        }
        Object existing = INSTANCES.putIfAbsent(ref, instance);
        if (existing != null && existing != instance) {
            // 不覆盖,但**要说出来** -- 否则"为什么我的实例不是刚造的那个"无从查起.
            dev.multiloader.common.Log.warn(
                    "入口点 {} 被重复分发；保留先前实例，忽略新的 {}（避免同一入口点出现两份实例）",
                    ref.className(), instance.getClass().getName());
        }
    }

/** 已分发的实例;未分发返回 null(调用方负责把它变成响亮的失败). */
    static Object dispatched(EntrypointRef ref) {
        return INSTANCES.get(ref);
    }
}
