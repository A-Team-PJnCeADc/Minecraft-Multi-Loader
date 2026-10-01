package dev.multiloader.mixin;

import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.classloader.TransformingClassLoader;
import dev.multiloader.common.GameNamespace;
import dev.multiloader.common.Log;
import dev.multiloader.common.Side;
import dev.multiloader.mixin.platform.MultiLoaderMixinService;
import dev.multiloader.mixin.platform.MultiLoaderMixinServiceBootstrap;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Mixin 的宿主.
 * <p>两条不可让步的规则(26.3 未混淆带来的直接结论):
 * <ul>
 *   <li><b>remap 恒为 false</b>,不提供开关.26.3 的字节码就是官方名,
 *       没有"需要重映射的名字"这回事.留一个开关只会让人误以为它能解决别的问题.</li>
 *   <li><b>核心层不引用任何映射类型</b>(INV-1 的精神).
 *       本类只和 Mixin 的公开 API 打交道.</li>
 * </ul>
*/
public final class MixinService {

/** Mixin 用于**确定性**指定平台服务的系统属性. */
    static final String PROPERTY_BOOTSTRAP_SERVICE = "mixin.bootstrapService";
    static final String PROPERTY_SERVICE = "mixin.service";

/** 26.3 未混淆 ->恒为 false. */
    public static final boolean MODERN_REMAP = false;

    public record MixinAudit(int configCount, int declarationCount,
                             List<MixinConflictDetector.Conflict> conflicts) {
        public boolean hasFatalConflicts() {
            return conflicts.stream()
                    .anyMatch(c -> c.severity() == MixinConflictDetector.Severity.ERROR);
        }
    }

    private final MixinConflictDetector conflictDetector = new MixinConflictDetector();

    private boolean initialized;
    private boolean bootstrapped;
    private boolean remapEnabled = MODERN_REMAP;
    private List<MixinConfigManager.MixinConfig> configs = List.of();
    private MixinTransformerProcessor transformerProcessor;

/**
     * 加载器层初始化.此时**不**启动 Mixin  那时游戏类加载器刚建好,
     * 但还没有任何 mod 配置被聚合.
*/
    public void init(TransformingClassLoader gameLoader, GameNamespace namespace) {
        if (initialized) {
            throw new IllegalStateException("MixinService already initialized");
        }
        if (namespace != GameNamespace.OFFICIAL) {
            // 混淆环境下 Mixin 必须开 remap 且需要 refmap,那是兼容层的职责.
            // 明确拒绝,而不是"带着错误的假设继续跑".
            // TODO(core): [未实现机制] 前置条件 旧版兼容层落地后此分支将改为真实实现
            String message = "MixinService.init 尚未支持：Mixin 在 " + namespace
                    + " 命名空间下需要 remap 与 refmap，而旧版（混淆时代）兼容层本期未实现。"
                    + "（见 docs/00-architecture.md：本加载器目标 26.x，无混淆）";
            Log.warn("{}", message);
            throw new UnsupportedOperationException(message);
        }

        // 必须在 MixinBootstrap.init() 之前注入:Mixin 启动过程中就会用
        // 我们的 IClassProvider 加载类,那时拿不到游戏层类加载器就全是错.
        // 但允许为 null:init() 也可能在"还没有游戏类加载器"的场合被调用
        // (例如只做策略与聚合,或单元测试).真正的硬性要求发生在 bootstrap() 
        // 那时若仍未注入,Mixin 会明确报错而不是静默退化.
        if (gameLoader != null) {
            MultiLoaderMixinService.installGameLoader(gameLoader);
        } else {
            Log.warn("MixinService initialized without a game class loader; "
                    + "Mixin bootstrap will fail if any mixin config is present");
        }

        this.initialized = true;
        this.remapEnabled = MODERN_REMAP;
        Log.info("MixinService initialized: namespace={} remap={} gameLoader={}",
                namespace, remapEnabled, gameLoader == null ? "<none>" : gameLoader.loaderName());
    }

    public void addConfigurations(List<MixinConfigManager.MixinConfig> mixinConfigs) {
        this.configs = List.copyOf(mixinConfigs);
    }

    public List<MixinConfigManager.MixinConfig> configs() {
        return configs;
    }

    public MixinAudit audit() {
        return new MixinAudit(configs.size(),
                configs.stream().mapToInt(c -> c.declarations().size()).sum(),
                conflictDetector.detect(configs));
    }

    public boolean isRemapEnabled() {
        return remapEnabled;
    }

/** 加载器层是否已初始化({@code init()} 是否跑过). */
    public boolean isInitialized() {
        return initialized;
    }

    public boolean isBootstrapped() {
        return bootstrapped;
    }

/**
     * 真正启动 Mixin 子系统.
     * <p>没有 mixin 配置时**不**启动:Mixin 一启动就会注册一堆全局状态与
     * 类转换钩子,对一个没有任何 mixin 的运行时来说纯是额外风险面.
*/
    public void bootstrap(Side side) {
        if (!initialized) {
            throw new IllegalStateException("MixinService.init() must run before bootstrap()");
        }
        if (bootstrapped) {
            throw new IllegalStateException("MixinService is already bootstrapped");
        }

        if (configs.isEmpty()) {
            Log.info("No mixin configurations collected — skipping Mixin bootstrap");
            bootstrapped = true;
            return;
        }

        // 走系统属性指定平台服务,而不是依赖 ServiceLoader:
        // ServiceLoader 用"发起方类的类加载器"去找 META-INF/services,
        // 在我们的三层类加载器图里这个落点不直观,而属性是确定的.
        // (META-INF/services 仍然随 jar 提供,作为声明与兜底.)
        System.setProperty(PROPERTY_BOOTSTRAP_SERVICE, MultiLoaderMixinServiceBootstrap.class.getName());
        System.setProperty(PROPERTY_SERVICE, MultiLoaderMixinService.class.getName());

        // 26.3 未混淆:没有 refmap,也不需要重映射
        System.setProperty("mixin.env.remapRefMap", "false");

        MixinBootstrap.init();

        MultiLoaderMixinService platformService = MultiLoaderMixinService.instance();
        if (platformService == null) {
            throw new IllegalStateException(
                    "Mixin did not instantiate our platform service. Check that "
                            + MultiLoaderMixinService.class.getName() + " is loadable and that the "
                            + PROPERTY_SERVICE + " property took effect.");
        }
        platformService.setMixinConfigNames(configs.stream()
                .map(MixinConfigManager.MixinConfig::configName).toList());

        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(side.isClient()
                ? MixinEnvironment.Side.CLIENT
                : MixinEnvironment.Side.SERVER);

        for (MixinConfigManager.MixinConfig config : configs) {
            Mixins.addConfiguration(config.configName());
            Log.info("  mixin config registered: {} ({})", config.configName(), config.origin());
        }

        // 把 Mixin 的环境相推进到 DEFAULT 
        // 这一步不做的话,mixin 会**完全静默地不生效**,而且没有任何报错.
        // 原因是 Mixin 内部用**身份比较**决定配置归属:
        //     MixinConfig.select(env) { return this.env == env; }
        // 配置对象在 addConfiguration 时捕获 getDefaultEnvironment()(DEFAULT 相的环境),
        // 而转换时 MixinTransformer 传入的是 getCurrentEnvironment()(当前相的环境).
        // MixinBootstrap.init() 之后当前相是 PREINIT,与 DEFAULT 是两个不同实例,
        // 于是每个配置在每次转换时都被判为"不属于本环境",一直被跳过 
        // 外部只看到 inspected 很多,transformed 恒为 0.
        // gotoPhase 是包私有的,只能反射调用.这不是绕路:Mixin 自己的平台
        // (Fabric 的 FabricLauncherBase.finishMixinBootstrapping)也是这么做的.
        gotoPhase(MixinEnvironment.Phase.INIT);
        gotoPhase(MixinEnvironment.Phase.DEFAULT);

        Log.info("Mixin environment phase advanced to {} (side={})",
                MixinEnvironment.getCurrentEnvironment().getPhase(),
                MixinEnvironment.getCurrentEnvironment().getSide());

        // 自检:Mixin 能不能读到混入类的注解 
        // Mixin 通过我们的 IClassBytecodeProvider 读取每个混入类的 @Mixin 注解,
        // 据此才知道"这个 mixin 要作用于哪个目标类".这条路径一旦失败,
        // 表现是 **mixin 完全静默不生效且零报错**(配置被选中,mixin 被"准备",
        // 就是找不到目标),是最难查的一类故障.所以主动验一次并把结果写进日志.
        selfCheckMixinBytecodeAccess(platformService);

        // ASM 份数自检(陷阱:两份 ASM 会让 ClassNode 类型不兼容)
        logAsmProvenance();

        this.transformerProcessor = new MixinTransformerProcessor(platformService);
        this.bootstrapped = true;

        Log.info("Mixin bootstrapped: platform={} side={} remap={} configs={}",
                platformService.getName(), environment.getSide(), remapEnabled, configs.size());
    }

/**
     * 验证 Mixin 的字节码提供者能读到每个混入类,并确认它声明的目标类.
     * <p>这条自检不是可选的仪式感:它就是"Mixin 是否具备生效条件"的直接探针.
     * 失败时我们宁可在这里明确报错,也不愿让用户面对一个毫无输出的空转加载器.
*/
    private void selfCheckMixinBytecodeAccess(MultiLoaderMixinService platformService) {
        for (MixinConfigManager.MixinConfig config : configs) {
            for (MixinConfigManager.MixinDeclaration declaration : config.declarations()) {
                String mixinClass = declaration.mixinClass();
                try {
                    var node = platformService.getBytecodeProvider().getClassNode(mixinClass);
                    Log.info("  mixin class accessible: {} -> ourTargets={} visibleAnnotations={} invisibleAnnotations={}",
                            mixinClass, declaration.targetClasses(),
                            node.visibleAnnotations, node.invisibleAnnotations);

                    // 用 Mixin 自己的代码读注解 
                    // 这是判定"Mixin 到底能不能看见 @Mixin"的唯一权威方式 
                    // 我们自己解析不算数,必须调它内部用的那个读取器.
                    probeMixinAnnotationReading(mixinClass, node);

                    if (declaration.targetClasses().isEmpty()) {
                        Log.warn("  mixin class {} declares no target — its @Mixin annotation may be missing "
                                + "or unreadable (node annotations: {})", mixinClass, node.visibleAnnotations);
                    }
                } catch (Throwable t) {
                    Log.error("  mixin class " + mixinClass + " is NOT readable by the bytecode provider. "
                            + "Mixin will silently skip it. Class path roots must contain the mod jar, "
                            + "and the class must exist under the config's package.", t);
                }
            }
        }
    }

/**
     * 确认 ASM 只有**一份**,且 Mixin,我们的管道,游戏层解析到的是同一个类.
     * <p>为什么必须验:Mixin 与我们的处理器都靠 ASM 的 {@code ClassNode} 交换数据.
     * 如果游戏层从自己的根里加载出第二份 ASM,那么
     * {@code org.objectweb.asm.tree.ClassNode} 这个类型在两个层里是**不同的类**,
     * 于是 Mixin 拿到的节点,和我们调用 Mixin 时构造的节点无法互通 
     * 症状是各种 {@code ClassCastException} 或者"注解凭空消失",且很难归因.
*/
    private static void logAsmProvenance() {
        reportClassOrigin("ASM ClassReader", org.objectweb.asm.ClassReader.class);
        reportClassOrigin("ASM ClassNode", org.objectweb.asm.tree.ClassNode.class);
        reportClassOrigin("Mixin MixinEnvironment", MixinEnvironment.class);

        // 决定性检查:让游戏层去解析 ASM,看它拿到的是不是同一个类
        Class<?> fromGameLayer;
        try {
            fromGameLayer = Class.forName("org.objectweb.asm.tree.ClassNode", false,
                    MultiLoaderMixinService.gameLoader());
        } catch (ClassNotFoundException | IllegalStateException e) {
            Log.warn("  could not resolve ClassNode through the game layer: {}", e.toString());
            return;
        }
        boolean sameIdentity = fromGameLayer == org.objectweb.asm.tree.ClassNode.class;
        Log.info("  game-layer ClassNode identity: {} (same instance as platform: {})",
                fromGameLayer, sameIdentity);
        if (!sameIdentity) {
            Log.error("  TWO ASM COPIES DETECTED — Mixin and our pipeline would exchange "
                    + "incompatible ClassNode types. Check the game layer's class path roots.");
        }
    }

    private static void reportClassOrigin(String label, Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        Log.info("  {} <- {} (loader={})", label,
                source == null ? "<bootstrap>" : source.getLocation(),
                type.getClassLoader());
    }

/**
     * 用 Mixin 自己的读取器判定它能否看见 {@code @Mixin},并复现它的节点拷贝链.
     * <p>排查 {@code readDeclaredTargets()} 返回空时,这条是唯一权威的判定:
     * 我们自己解析注解不算数,必须调用 Mixin 内部实际使用的
     * {@code Annotations.getInvisible(node, Mixin.class)}.
     * <p>同时复现 {@code MixinInfo.createClassNode()} 的做法
     * ({@code classNode.accept(new ClassNode())} 拷贝),
     * 因为 {@code readDeclaredTargets} 读的是**拷贝后**的 validationClassNode,
     * 而不是 provider 直接返回的那个节点.这一步能区分
     * "注解本来就读不到" 和 "读了但拷贝过程中丢了".
*/
    private static void probeMixinAnnotationReading(String mixinClass, org.objectweb.asm.tree.ClassNode node) {
        try {
            // ① Mixin 内部用的读取器(invisible 通道)
            Method getInvisible = Class.forName("org.spongepowered.asm.util.Annotations")
                    .getMethod("getInvisible", org.objectweb.asm.tree.ClassNode.class, Class.class);
            Object viaMixin = getInvisible.invoke(null, node, org.spongepowered.asm.mixin.Mixin.class);
            Log.info("    {} Annotations.getInvisible(node, Mixin.class) -> {}",
                    mixinClass, viaMixin == null ? "<null>  <- Mixin 看不见 @Mixin" : viaMixin);

            // ② 复现 MixinInfo.createClassNode(0) 的拷贝
            org.objectweb.asm.tree.ClassNode copy = new org.objectweb.asm.tree.ClassNode();
            node.accept(copy);
            Object viaMixinOnCopy = getInvisible.invoke(null, copy, org.spongepowered.asm.mixin.Mixin.class);
            Log.info("    {} after accept() copy -> invisibleAnnotations={} getInvisible={}",
                    mixinClass, copy.invisibleAnnotations,
                    viaMixinOnCopy == null ? "<null>  <- 拷贝过程中丢了注解" : viaMixinOnCopy);

            // ③ 若 Mixin 拿得到,再看它解析出的 targets 是什么
            if (viaMixin != null) {
                Method getValue = Class.forName("org.spongepowered.asm.util.Annotations")
                        .getMethod("getValue", org.objectweb.asm.tree.AnnotationNode.class, String.class);
                Object targets = getValue.invoke(null, viaMixin, "targets");
                Log.info("    {} Mixin reads 'targets' = {}", mixinClass, targets);
            }
        } catch (Throwable t) {
            Log.warn("    annotation-reading probe failed for {}", mixinClass, t);
        }
    }

/**
     * 反射推进 Mixin 的环境相.
     * <p>{@code MixinEnvironment.gotoPhase(Phase)} 是包私有的,只能反射调用.
     * 这不是权宜之计:Mixin 官方平台(Fabric 的
     * {@code FabricLauncherBase.finishMixinBootstrapping()})做的完全一样.
     * Mixin 没有为"自己控制类加载的启动器"提供公开的相推进 API.
     * @param phase 目标相;必须按 INIT ->DEFAULT 顺序推进
*/
    private static void gotoPhase(MixinEnvironment.Phase phase) {
        try {
            Method method = MixinEnvironment.class.getDeclaredMethod("gotoPhase",
                    MixinEnvironment.Phase.class);
            method.setAccessible(true);
            method.invoke(null, phase);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "failed to advance Mixin environment phase to " + phase
                            + " — without this, every mixin config would be silently skipped", e);
        }
    }

/**
     * 供管道使用的处理器.
     * @throws IllegalStateException 未 bootstrap
*/
    public ClassProcessor transformerProcessor() {
        if (transformerProcessor == null) {
            throw new IllegalStateException(
                    "no mixin configurations were bootstrapped — call isBootstrapped() first, "
                            + "or check configs() before wiring the processor into the pipeline");
        }
        return transformerProcessor;
    }

/** 诊断用:Mixin 转换器的活动统计. */
    public String describeTransformerActivity() {
        return transformerProcessor == null ? "<not bootstrapped>" : transformerProcessor.describe();
    }
}
