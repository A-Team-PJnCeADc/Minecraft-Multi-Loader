package dev.multiloader.agent;

import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.IEntrypointQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link IEntrypointQuery} 的实现  按**原始 entrypoint key** 查出**已分发**的实例.
 * <p><b>枚举与环境过滤完全复用 {@link LoaderBootstrap#collectAll}</b>,本类只做
 * "按 sourceKey 筛 + 从实例缓存取"两步.桥接层因此**零实例化逻辑** 
 * 实例化公式(含带参构造器的服务注入)只存在于分发路径里,写两遍必然会有一天只改一处.
 * <p><b>按 sourceKey 而不是 phase</b>:见 {@link IEntrypointQuery} 的说明 
 * key 与 phase 是不同的键,按 phase 查需要编一张映射表,而那张表的每一格
 * 都是本工程替上游做的决定.
*/
final class EntrypointQueryImpl implements IEntrypointQuery {

/**
     * 惰性取加载器状态,而不是构造时捕获.
     * <p><b>为什么必须是惰性</b>:本实现是在 {@code onGameBoot} 时构造的,而那一刻
     * {@code BootstrapState} **还不存在**(它之后才创建).若构造时取快照,要么拿到 null,
     * 要么得用空壳  空壳的表现是"查询永远返回空列表",安静得像是"这个 mod 没声明入口点".
     * <p>这与 {@code LoaderContextImpl} 对 modFiles 用 {@code Supplier} 是同一个理由:
     * 构造时机与数据就绪时机不一致时,捕获快照会把"当时还没有"永久固化.
*/
    private final Supplier<LoaderBootstrap.BootstrapState> state;

    EntrypointQueryImpl(Supplier<LoaderBootstrap.BootstrapState> state) {
        this.state = state;
    }

    @Override
    public List<IEntrypointQuery.DispatchedEntrypoint> query(String sourceKey, Class<?> type) {
        if (sourceKey == null) {
            throw new IllegalArgumentException("IEntrypointQuery.query(null, …)");
        }
        if (type == null) {
            throw new IllegalArgumentException("IEntrypointQuery.query(…, null)");
        }
        LoaderBootstrap.BootstrapState current = state.get();
        if (current == null) {
            throw new IllegalStateException(
                    "IEntrypointQuery 在加载器状态建立之前被调用：入口点查询只能在加载器启动后使用。");
        }

        List<IEntrypointQuery.DispatchedEntrypoint> result = new ArrayList<>();
        for (LoaderBootstrap.PendingEntrypoint pending : LoaderBootstrap.collectAll(current)) {
            EntrypointRef ref = pending.ref();
            if (!sourceKey.equals(ref.sourceKey())) {
                continue;
            }
            Object instance = DispatchedEntrypoints.dispatched(ref);
            if (instance == null) {
                // 匹配到了声明,但它在自己的 LifecyclePhase 还没被分发,实例还不存在.
                // 抛异常而不是返回空列表:空列表会让调用方以为"这个 mod 没声明该 key",
                // 从而走错分支  而正确做法是等它分发完再来查.
                throw new IllegalStateException(
                        "入口点尚未分发到对应生命周期阶段：mod '" + ref.modId() + "' 的 "
                                + ref.className() + " 声明在 key '" + sourceKey + "' 下，"
                                + "但它的阶段 " + ref.phase() + " 还没被分发，实例尚不存在。"
                                + "请在对应的 LifecyclePhase 之后再查询。");
            }
            if (type.isInstance(instance)) {
                // modId 从 ref 上取  分发登记时它就在 key 里,不需要反推.
                result.add(new IEntrypointQuery.DispatchedEntrypoint(ref.modId(), instance));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String toString() {
        LoaderBootstrap.BootstrapState current = state.get();
        return "EntrypointQueryImpl[" + (current == null ? "state not ready"
                : current.modList().size() + " mod file(s)") + "]";
    }
}
