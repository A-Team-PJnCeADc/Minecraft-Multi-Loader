package dev.multiloader.bridge.fabric.loader;

import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.lifecycle.IEntrypointQuery;
import dev.multiloader.api.service.IGameContext;
import dev.multiloader.api.service.ILoaderContext;
import dev.multiloader.bridge.fabric.FabricMultiLoaderExtension;
import dev.multiloader.bridge.fabric.meta.MinimalFabricModContainer;
import dev.multiloader.common.Log;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link FabricLoader} 的实现 -- mod 通过
 * {@code FabricLoader.getInstance().getXxx()} 静态访问来到这里.
 * <p><b>数据来源</b>:{@link FabricMultiLoaderExtension} 在生命周期时点捕获的
 * {@link ILoaderContext}.Fabric 的访问是静态无参的,没有任何地方能接收参数,
 * 所以那条反向通道是唯一的数据入口.
 * <p><b>为什么每个方法都重新读一次上下文,而不是在构造时缓存</b>:
 * 本类的实例由 {@code FabricLoaderImpl} 的静态初始化块创建,而**那一刻不可控**
 * (可能是 mod 的 onInitialize 里,也可能更早被别的代码触发).
 * 构造时取快照会把"当时还没有 mod"永久固化 -- 表现为 mod 永远看不到任何 mod 列表,
 * 且毫无线索.
 * <p><b>上下文尚未就绪时的行为</b>:{@code ILoaderContext} 为 null 说明
 * 核心层还没调用过扩展钩子,属于**装配错误**(不是"暂时没有数据").
 * 这种情况下一个都不该静默返回空 -- 见 {@link #requireContext()}.
*/
public class MultiLoaderFabricLoader implements FabricLoader {

    @Override
    public boolean isModLoaded(String id) {
        return findModFile(id) != null;
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        IModFile file = findModFile(id);
        return file == null ? Optional.empty() : Optional.of(new MinimalFabricModContainer(file));
    }

    @Override
    public Collection<ModContainer> getAllMods() {
        return modFiles().stream().<ModContainer>map(MinimalFabricModContainer::new).toList();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return requireContext().isDevelopment();
    }

    @Override
    public String getRawGameVersion() {
        return requireContext().minecraftVersion();
    }

    @Override
    public Path getGameDir() {
        return requireContext().gameRoot();
    }

    @Override
    public File getGameDirectory() {
        return requireContext().gameRoot().toFile();
    }

/**
     * 配置目录.
     * <p><b>是 gameRoot/config 而不是 modsDir</b>:Fabric 的约定是游戏根下的
     * {@code config/}.这两个路径在本工程的启动布局里**同时存在且不同**,
     * 用错会得到一个"看起来像但不对"的路径 -- 而路径错误是那种跑很久
     * 才发现的类型(mod 的配置会写到 mods/ 里,谁也不会去那儿找).
*/
    @Override
    public Path getConfigDir() {
        return requireContext().gameRoot().resolve("config");
    }

    @Override
    public File getConfigDirectory() {
        return getConfigDir().toFile();
    }

    // 明确拒绝 -- 每条理由不同

/**
     * 侧别.
     * <p>数据来自 {@code ILoaderContext.side()}(GameSide),映射到 Fabric 的
     * {@code EnvType}.两个枚举都只有 CLIENT/SERVER 两值,switch 可穷尽 --
     * 若任一侧将来新增取值,这里会编译失败而不是静默取错.
*/
    @Override
    public EnvType getEnvironmentType() {
        return switch (requireContext().side()) {
            case CLIENT -> EnvType.CLIENT;
            case SERVER -> EnvType.SERVER;
        };
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        // TODO(bridge): [未实现机制] 前置条件 
        String message = "MultiLoaderFabricLoader.getLaunchArguments 尚未支持："
                + "游戏启动参数的来源在启动器侧（LaunchProfileBuilder），未接入 ILoaderContext。"
                + "注意 StartupArgs.raw 是 **agent 参数**，不是游戏参数，用它冒充会给出错误内容。"
                + "（见 docs/02-bridge-design.md §6 本期范围）";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

    @Override
    public Object getGameInstance() {
        // TODO(bridge): [未实现机制] 前置条件 
        String message = "MultiLoaderFabricLoader.getGameInstance 尚未支持："
                + "需要「游戏实例注册」机制（游戏主类启动后把实例登记到某处）。本工程尚无该机制。"
                + "（见 docs/02-bridge-design.md §6 本期范围）";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

/**
     * 按 entrypoint key 取实例.
     * <p>数据来自 {@code IEntrypointQuery}(经 {@code IGameContext} 的服务通道取到)--
     * 桥接层**零实例化逻辑**.实例由加载器的分发器在对应 {@code LifecyclePhase}
     * 造好并缓存,这里只取用.若让桥接自己实例化,同一段初始化会跑两遍,
     * 而"跑了两遍"只有依赖初始化次数的 mod 才看得出来.
*/
    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        // type::cast 而不是裸转型:返回类型是 List<T>,逐个 cast 让类型检查落在
        // 每个元素上(且不需要 @SuppressWarnings).
        return requireQuery().query(key, type).stream()
                .map(entry -> type.cast(entry.instance()))
                .toList();
    }

/**
     * 与 {@link #getEntrypoints} 同源,额外带上"谁提供了它".
     * <p>{@code getProvider()} 需要的 {@code ModContainer} 由 modId 查出 --
     * 复用已有的 {@link #getModContainer(String)}.**不从实例反推 owner**:
     * 比对类加载器在同一层服务多个 mod 时会指错,而错的 owner 看起来有值,类型也对.
*/
    @Override
    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        return requireQuery().query(key, type).stream()
                .<EntrypointContainer<T>>map(entry -> {
                    ModContainer provider = getModContainer(entry.modId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "入口点声明属于 mod '" + entry.modId()
                                            + "'，但该 mod 不在已发现列表里"
                                            + "（元数据与入口点的 modId 不一致？）"));
                    return new MinimalEntrypointContainer<>(type.cast(entry.instance()), provider);
                })
                .toList();
    }

/** 最小的 {@link EntrypointContainer} 实现:实例 + 提供者. */
    private record MinimalEntrypointContainer<T>(T entrypoint, ModContainer provider)
            implements EntrypointContainer<T> {

        @Override
        public T getEntrypoint() {
            return entrypoint;
        }

        @Override
        public ModContainer getProvider() {
            return provider;
        }

        @Override
        public String getDefinition() {
            // 默认实现返回空串.这里给出能定位到 provider 与实现类的内容.
            return provider.getMetadata().getId() + " -> " + entrypoint.getClass().getName();
        }
    }

/**
     * 对每个入口点实例调用 invoker.
     * <p>与 {@link #getEntrypoints} 同源(都读缓存实例),所以**不会**额外实例化.
     * 这一点在这里格外重要:core 在生命周期阶段"推",mod 主动"拉",
     * 两条路若各造一份,同一入口点就会有两个实例 -- 而它们各自的字段状态会分叉.
*/
    @Override
    public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
        if (invoker == null) {
            throw new IllegalArgumentException("invokeEntrypoints(…, null)");
        }
        for (T instance : getEntrypoints(key, type)) {
            invoker.accept(instance);
        }
    }

    @Override
    public ObjectShare getObjectShare() {
        // TODO(bridge): [未实现机制] 前置条件 
        String message = "MultiLoaderFabricLoader.getObjectShare 尚未支持："
                + "跨 mod 共享对象的机制未实现（需要一套按 key 的共享注册表与其生命周期）。"
                + "（见 docs/02-bridge-design.md §6 本期范围）";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

/**
     * 映射解析器.
     * <p><b>这一条不是"还没实现",而是与架构冲突.</b> 本工程的前提是 26.x 无混淆
     * ->不存在映射系统(无 MappingConverter / NamespaceGraph).
     * {@code MappingResolver} 的全部语义都建立在"存在多套命名空间"之上.
     * <p>返回一个恒等映射的假实现是**最坏的选择**:它能跑,但语义错 --
     * mod 会在真正需要映射关系的场合出现难以归因的错配.
*/
    @Override
    public MappingResolver getMappingResolver() {
        throw new UnsupportedOperationException(
                "FabricLoader.getMappingResolver() 不可用：本加载器目标为 26.x（无混淆），"
                        + "不存在映射系统，因此没有命名空间可解析。这是架构选择，不是缺失的功能。");
    }

/**
     * 取得核心层注册的入口点查询服务.
     * <p>两道失败要分开报:**上下文没建立**(扩展钩子没被调用 / bridge 不在运行时类路径)
     * 与**服务没注册**(LoaderBootstrap 里漏了 registerService)-- 成因不同,修法不同,
     * 合成一句会让人查错方向.
*/
    private IEntrypointQuery requireQuery() {
        IGameContext context = FabricMultiLoaderExtension.gameContext();
        if (context == null) {
            throw new IllegalStateException(
                    "entrypoint 查询不可用：加载器上下文尚未建立"
                            + "（检查核心层是否调用了 MultiLoaderExtension.onGameBoot，"
                            + "以及 bridge-fabric-api 是否在运行时类路径上）。");
        }
        IEntrypointQuery query = context.getService(IEntrypointQuery.class);
        if (query == null) {
            throw new IllegalStateException(
                    "entrypoint 查询不可用：核心层没有注册 IEntrypointQuery 服务"
                            + "（检查 LoaderBootstrap 里 gameContext.registerService(...) 那一步）。");
        }
        return query;
    }

/**
     * 加载器上下文.
     * <p>为 null 说明核心层没调用过扩展钩子({@code onLoaderInit} / {@code onGameBoot}),
     * 属于**装配错误**.抛异常而不是让所有方法返回空:空会让 mod 以为"这个实例里
     * 什么都没装",从而走错分支,而归因指向 mod 自己.
*/
    private ILoaderContext requireContext() {
        ILoaderContext ctx = FabricMultiLoaderExtension.loaderContext();
        if (ctx == null) {
            throw new IllegalStateException(
                    "FabricLoader 被访问时加载器上下文尚未建立 检查核心层是否调用了 "
                            + "MultiLoaderExtension.onLoaderInit，以及 bridge-fabric-api 是否在运行时类路径上。");
        }
        return ctx;
    }

    private List<IModFile> modFiles() {
        return requireContext().modFiles();
    }

    private IModFile findModFile(String modId) {
        if (modId == null) {
            return null;
        }
        for (IModFile file : modFiles()) {
            for (var metadata : file.getMetadataList()) {
                if (modId.equals(metadata.modId())) {
                    return file;
                }
            }
            // 同时按 provides 匹配:Fabric 里"提供了某个 modId"也算被加载.
            var extras = file.getExtension(dev.multiloader.api.metadata.IModFileMetadata.class);
            if (extras.isPresent() && extras.get().provides().contains(modId)) {
                return file;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        ILoaderContext ctx = FabricMultiLoaderExtension.loaderContext();
        return "MultiLoaderFabricLoader[" + (ctx == null ? "context not ready" : "ready") + "]";
    }
}
