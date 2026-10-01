package dev.multiloader.mixin.platform;

import dev.multiloader.classloader.TransformingClassLoader;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * MultiLoader 的 Mixin 平台服务.
 * <p>Mixin 通过 {@code IMixinServiceBootstrap}(我们在 {@code META-INF/services} 里注册,
 * 并在 {@code MixinService.bootstrap()} 里用系统属性显式指定)发现本类,
 * 然后由它实例化.因此本类必须有无参构造器,且**不能**通过构造器注入依赖 
 * 需要的东西一律靠静态注入(见 {@link #installGameLoader}).
 * <p>继承 {@link MixinServiceAbstract} 而不是从接口从零实现:基类已经提供了
 * phase 管理,重入锁,日志,容器聚合等 11 个方法,我们只需补齐
 * 平台相关的 13 个.
 * <p>刻意返回 {@code null} 的那几个(transformer provider / class tracker /
 * audit trail / feature validator / advice provider)是**可选**扩展点:
 * 它们在 Mixin 内部都有 null 检查.返回 null 表示"本平台不提供该能力",
 * 而不是"忘了实现" 这是 Mixin 平台的正常做法.
*/
public class MultiLoaderMixinService extends MixinServiceAbstract {

/**
     * 游戏层类加载器.
     * <p>刻意用具体类型 {@link TransformingClassLoader} 而不是 {@code ClassLoader}:
     * {@link GameBytecodeProvider} 需要调用 {@code getClassBytes()} 
     * 那是"读原始字节,不触发转换"的逃逸口,只有本项目的游戏层加载器有.
     * 用宽类型会逼出一次向下转型.
*/
    private static volatile TransformingClassLoader gameLoader;
    private static volatile MultiLoaderMixinService instance;

    private final GameClassProvider classProvider = new GameClassProvider();
    private final GameBytecodeProvider bytecodeProvider = new GameBytecodeProvider();

    private volatile IMixinTransformerFactory transformerFactory;
    private volatile IMixinTransformer transformer;
    private volatile List<String> mixinConfigNames = List.of();

/**
     * 在 {@code MixinBootstrap.init()} **之前**注入游戏层类加载器.
     * <p>为什么必须提前:Mixin 在启动过程中就会通过 {@link GameClassProvider}
     * 加载类,那时如果还没注入,拿到的会是 Mixin 自己的加载器 
     * 混入类将无法解析,错误会表现为"mixin 配置里的类找不到".
*/
    public static void installGameLoader(TransformingClassLoader loader) {
        gameLoader = Objects.requireNonNull(loader, "loader");
    }

/** 游戏层类加载器;未注入时抛异常而不是静默退化. */
    public static TransformingClassLoader gameLoader() {
        TransformingClassLoader loader = gameLoader;
        if (loader == null) {
            throw new IllegalStateException(
                    "game class loader was not installed — "
                            + "MultiLoaderMixinService.installGameLoader() must run before MixinBootstrap.init()");
        }
        return loader;
    }

/** 当前服务实例(Mixin 构造之后才有值). */
    public static MultiLoaderMixinService instance() {
        return instance;
    }

    public MultiLoaderMixinService() {
        instance = this;
    }

    // 身份

    @Override
    public String getName() {
        return "MultiLoader";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    // 平台能力

    @Override
    public IClassProvider getClassProvider() {
        return classProvider;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return bytecodeProvider;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        // 我们不用 Mixin 的"旧式 transformer 注册表"转换由自己的 ClassProcessor 管道驱动
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        // 需要该项的是 Mixin 的 synthetic class 追踪(@Accessor 等)在部分平台下的路径;
        // Mixin 内部对 null 有检查.暂不提供.
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return null;
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        // 平台代理用于与具体启动器(Forge/FML/ModLauncher)集成.
        // 我们自己就是启动器,不需要代理.
        return List.of();
    }

/**
     * 提供**真正会输出**的日志适配器.
     * <p>必须覆写:基类返回的是 {@code LoggerAdapterDefault},
     * 它的自我描述是 "Default Logger (No Logging)"  所有方法空实现.
     * 那样会让 Mixin 的每一条警告与错误都消失,
     * 外部只能看到"mixin 没生效"而拿不到任何原因.
*/
    @Override
    protected ILogger createLogger(String name) {
        return new MultiLoaderLogger(name);
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        // 必须非 null:MixinPlatformManager.init() 拿到它之后立刻解引用
        return new MultiLoaderContainerHandle("multiloader", "MultiLoader primary container");
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        // 顺序要紧:mixin 配置 JSON 住在 **mod jar** 里(游戏层),
        // 先问游戏层;游戏层没有才回退到 Mixin 自己那一层
        // (Mixin 会用这个接口读自己打包的配置文件与 refmap).
        InputStream stream = null;
        if (gameLoader != null) {
            stream = gameLoader.getResourceAsStream(name);
        }
        if (stream == null) {
            stream = MultiLoaderMixinService.class.getResourceAsStream('/' + name);
        }
        return stream;
    }

    // transformer 生命周期

/**
     * 接收 Mixin 通过 {@code MixinBootstrap.offerInternals()} 送来的 transformer 工厂.
     * <p>Mixin 的 {@code MixinTransformer$Factory} 是**包私有**的,我们无法直接 new;
     * Mixin 反射造好之后用这个回调交给我们.工厂在这里只做保存 
     * 真正的实例化推迟到第一次转换请求(与 ModLauncher 平台一致),
     * 那时环境与配置才都已就绪.
*/
    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            this.transformerFactory = factory;
        }
        super.offer(internal);
    }

/** 记录本平台要加载的 mixin 配置名(供主容器上报). */
    public void setMixinConfigNames(List<String> configNames) {
        this.mixinConfigNames = List.copyOf(configNames);
    }

/**
     * 取得 transformer,必要时惰性创建.
     * <p>为什么惰性而不是在 {@code init()} 里就建好:transformer 的构造器会在
     * 当前环境上调用 {@code setActiveTransformer(this)},而环境必须已经进入
     * 正确的 phase.推迟到第一次转换时,环境一定已经就绪.
*/
    public synchronized IMixinTransformer transformer() {
        if (this.transformer == null) {
            IMixinTransformerFactory factory = this.transformerFactory;
            if (factory == null) {
                throw new IllegalStateException(
                        "Mixin transformer factory was never offered — "
                                + "MixinBootstrap.init() probably did not run, or it could not find our "
                                + "IMixinServiceBootstrap (check META-INF/services and the mixin.bootstrapService property)");
            }
            this.transformer = factory.createTransformer();
        }
        return this.transformer;
    }

    public boolean hasTransformerFactory() {
        return this.transformerFactory != null;
    }
}
