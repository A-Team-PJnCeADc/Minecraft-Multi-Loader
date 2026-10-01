package dev.multiloader.api.lifecycle;

import java.util.List;

/**
 * 按**原始 entrypoint key** 查询已**实例化**的入口点.
 * <p><b>为什么按 key 查,而不是按 {@link LifecyclePhase} 查</b>:两者是**不同的键**.
 * <pre>
 *   LifecyclePhase   本加载器的**分发时点**(CONSTRUCT / INIT / SIDED_SETUP)
 *   sourceKey        上游声明的**入口点键**(Fabric 的 main / client / server)
 * </pre>
 * Fabric 的 {@code FabricLoader.getEntrypoints("main", ...)} 用的是后者.若改按 phase 查,
 * 就必须编一张 {@code key -> phase} 映射表,而那张表的每一格都是本工程替上游做的决定 --
 * 所以 {@link EntrypointRef#sourceKey()} 保留原始 key,查询直接按它匹配,零映射.
 * <p><b>返回的是实例,不是 Ref -- 实例由加载器的分发器提供,查询不负责创建.</b>
 * 这样做的关键收益是:**同一个入口点在整个进程里只有一个实例**.
 * 否则桥接层要自己实例化(因为它得响应 mod 的主动查询),就会出现
 * "core 在生命周期阶段造一个,mod 查询时又造一个" -- 同一段初始化代码跑两遍,
 * 而两边的差异只有依赖它的 mod 才会发现.
 * <p><b>未分发时抛异常,不返回空列表</b>:入口点只在它的 {@link LifecyclePhase}
 * 被分发过之后才存在于缓存里.若在分发前查询就返回空列表,"还没到时候"与
 * "这个 mod 没声明该 key"就无法区分 -- 而两者的处理方式完全相反
 * (前者该等,后者该走别的分支).所以前者抛 {@link IllegalStateException}.
 * <p><b>只读</b>:实现不得改变加载器状态,也不需要自己加锁 -- 缓存由分发器维护.
*/
public interface IEntrypointQuery {

/**
     * 查出声明在 {@code sourceKey} 下,且**已经分发**的入口点实例.
     * @param sourceKey 上游的入口点键(如 {@code "main"} / {@code "client"} / {@code "server"}).
     *                  不得为 null.
     * @param type      要求的类型;只返回 {@code type.isInstance(instance)} 为真的实例.
     *                  不得为 null.
     * @return 匹配的实例(不可变列表,可能为空)-- 空表示"该 key 下确实没有符合类型的入口点",
     *         与"尚未分发"是两回事(后者抛异常).
     * @throws IllegalArgumentException {@code sourceKey} 或 {@code type} 为 null(调用方的编程错误)
     * @throws IllegalStateException    匹配到的入口点尚未分发到对应生命周期阶段
*/
    List<DispatchedEntrypoint> query(String sourceKey, Class<?> type);

/**
     * 一条已分发的入口点:**实例 + 它属于哪个 mod**.
     * <p><b>为什么必须带上 {@code modId}</b>:Fabric 的
     * {@code EntrypointContainer.getProvider()} 要回答"谁提供了这个入口点".
     * 仅凭实例反推 owner(例如比对它的类加载器)在同一加载器层服务多个 mod 时
     * 会指错,而"getProvider 返回了错的 mod"看起来有值,类型也对 -- 最难发现的一类.
     * {@code modId} 在分发登记时就已知,直接带出来即可,无需任何推断.
*/
    record DispatchedEntrypoint(String modId, Object instance) {
    }
}
