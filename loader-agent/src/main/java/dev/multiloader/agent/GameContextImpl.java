package dev.multiloader.agent;

import dev.multiloader.api.service.IGameContext;
import dev.multiloader.api.transform.ClassProcessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link IGameContext} 的核心侧实现:游戏层就绪后交给扩展的句柄.
 * <p>对应 {@code MultiLoaderExtension#onGameBoot} 那个时点:类加载图已建成,
 * Mod 已全部发现,生命周期尚未开始.
 * <p><b>服务注册用类型作键</b>:{@code registerService(Class, T)} / {@code getService(Class)}
 * 是一对最小契约.用 {@code Class} 而不是字符串键,是因为跨模块传递服务时
 * 类型本身就是最强的约定  字符串键会让"取错服务"表现为运行期转型失败,
 * 而类型键在 getService 处即可失败.
*/
final class GameContextImpl implements IGameContext {

    private final ClassLoader gameLoader;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    GameContextImpl(ClassLoader gameLoader) {
        this.gameLoader = gameLoader;
    }

    @Override
    public ClassLoader gameLoader() {
        return gameLoader;
    }

/**
     * 注册一个类转换处理器.
     * <p><b>本期明确拒绝,而不是收下却不用.</b> 收进一个没人读的列表 = 静默 no-op:
     * 扩展会以为自己的转换已生效,而实际上永远不会被调用  这正是本项目反复
     * 出现的那类失效形态.转换管道当前是固定的 `pipeline-probe@0 -> mixin@0`,
     * 要让外部处理器进来需要先定"插在哪个顺序位置"(AT/AW 也必须排在 Mixin 之前),
     * 那是独立的一步.
     * <p>抛异常而不是记警告:警告会被日志淹没,而调用方在本期**必须**知道
     * 自己的处理器不会生效,否则它会把"注入没生效"归因到自己的实现上.
*/
    @Override
    public void registerClassProcessor(ClassProcessor processor) {
        // TODO(core): [未实现机制] 前置条件
        // 不指向 docs/02 §6(那章讲的是桥接层本期范围).
        String message = "GameContextImpl.registerClassProcessor 尚未支持：转换管道当前固定为 "
                + "pipeline-probe@0 -> mixin@0，外部处理器的插入位置（顺序权重）还需先定。"
                + "你的处理器本期不会生效，故此处明确失败而不是静默收下。"
                + "（见 docs/00-architecture.md 的转换管线说明）";
        dev.multiloader.common.Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

    @Override
    public <T> void registerService(Class<T> type, T instance) {
        if (type == null || instance == null) {
            throw new IllegalArgumentException("registerService(null)");
        }
        // putIfAbsent 语义:同一类型被注册两次说明扩展之间有冲突,
        // 静默覆盖会让先注册者的行为凭空消失.明确报出来.
        Object previous = services.putIfAbsent(type, instance);
        if (previous != null) {
            throw new IllegalStateException("service already registered for " + type.getName()
                    + "（已有 " + previous.getClass().getName() + "）");
        }
    }

    @Override
    public <T> T getService(Class<T> type) {
        // 未注册时返回 null(接口契约如此).调用方按需判空 
        // 这里不抛异常:查询一个可选服务是否存在是正常用法.
        return type.cast(services.get(type));
    }

/** 供测试与诊断:已注册的服务类型. */
    Map<Class<?>, Object> registeredServices() {
        return Map.copyOf(services);
    }
}
