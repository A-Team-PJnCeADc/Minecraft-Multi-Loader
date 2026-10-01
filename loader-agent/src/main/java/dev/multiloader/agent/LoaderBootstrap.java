package dev.multiloader.agent;

import dev.multiloader.api.lifecycle.EntrypointRef;
import dev.multiloader.api.lifecycle.IEntrypointProvider;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.classloader.ClassLoaderGraph;
import dev.multiloader.classloader.ClassPathIndex;
import dev.multiloader.classloader.TransformingClassLoader;
import dev.multiloader.common.GameNamespace;
import dev.multiloader.common.Log;
import dev.multiloader.core.discovery.DirectoryModCandidateLocator;
import dev.multiloader.core.discovery.ModDiscoverer;
import dev.multiloader.core.resolve.LoadingModList;
import dev.multiloader.core.service.ServiceRegistry;
import dev.multiloader.api.service.IEntrypointServiceProvider;
import dev.multiloader.api.service.MultiLoaderExtension;
import dev.multiloader.mixin.MixinConfigManager;
import dev.multiloader.mixin.MixinService;
import dev.multiloader.api.locating.IModFileCandidateLocator;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 引导编排:把"探测 ->类加载器图 ->发现 mod ->Mixin 配置 ->入口点分发"串成一条链.
 * <p>这是加载器层与游戏层之间的唯一交接点.agent 在 premain 阶段跑到
 * {@link #dispatch(LifecyclePhase)} 之前为止;游戏层的事从
 * {@code dev.multiloader.launcher.Main} 调用 {@link #dispatch} 开始.
 * <p>状态放在静态引用里而不是靠参数传递,因为 agent 与 launcher.Main
 * 分属两个模块,两个调用时机,且 Q1 决策下它们是同一个 JVM,同一个加载器.
*/
public final class LoaderBootstrap {

/**
     * 引导结果.
     * <p>record 而不是可变对象:状态一旦产生就不再修改,避免"部分初始化".
*/
    public record BootstrapState(StartupArgs args,
                                 VersionDetector.ProbeResult probe,
                                 ClassLoaderGraph graph,
                                 LoadingModList modList,
                                 MixinService mixinService,
                                 List<MixinConfigManager.MixinConfig> mixinConfigs,
                                 PipelineProbe pipelineProbe) {

        public GameNamespace namespace() {
            return probe.namespace();
        }

        public TransformingClassLoader gameLoader() {
            return graph.gameLoader();
        }
    }

    private static final AtomicReference<BootstrapState> STATE = new AtomicReference<>();

    private LoaderBootstrap() {
    }

/**
     * premain 阶段调用.
     * @throws IllegalStateException      重复引导
     * @throws IllegalArgumentException   参数不合法
*/
    public static BootstrapState bootstrap(String rawAgentArgs, Instrumentation inst) {
        if (STATE.get() != null) {
            throw new IllegalStateException("MultiLoader already bootstrapped");
        }

        StartupArgs args = StartupArgs.parse(rawAgentArgs);
        Log.info("MultiLoader agent {} | {}", agentVersion(), args.summary());

        validate(args);

        // 1. 命名空间与补丁提供方探测
        VersionDetector.ProbeResult probe = VersionDetector.detect(args);
        Log.info("Detected: {}", probe.summaryLine());
        for (String line : probe.evidence()) {
            Log.info("  {}", line);
        }
        applyNamespacePolicy(probe);

        if (inst == null) {
            Log.warn("Instrumentation is null (ProbeTool / unit-test path); "
                    + "class retransformation-based features will be unavailable");
        }

        // 1.5 扩展通知:加载器层已就绪,Mod 尚未发现
        // 契约(ILoaderContext javadoc)写明此时 modFiles() 可能为空.
        // 必须排在 discover() **之前**,且必须早于下面的"建类加载器图":
        // 扩展的典型用途是注册 ClassProcessor / 声明额外 classpath 根,
        // 而图一旦建成,根集合就冻结了(ClassPathIndex 不支持事后插根).
        // 用 AtomicReference 持有 mod 列表:同一个 LoaderContextImpl 实例会一直
        // 传给扩展到 onGameBoot 之后,若在构造时取快照,早于发现的上下文会把
        // "空"永久固化 -- 扩展永远看不到任何 mod,且毫无线索.
        AtomicReference<List<IModFile>> discoveredMods = new AtomicReference<>(List.of());
        LoaderContextImpl loaderContext = new LoaderContextImpl(args, discoveredMods::get);
        for (MultiLoaderExtension extension : ServiceRegistry.loadAll(MultiLoaderExtension.class)) {
            try {
                extension.onLoaderInit(loaderContext);
            } catch (RuntimeException e) {
                // 一个扩展失败不该让整台机器起不来,但绝不静默:
                // 记 error 后继续,让其余扩展仍能工作.
                Log.error("Extension '" + extension.name() + "' failed in onLoaderInit", e);
            }
        }

        // 2. Mod 发现
        // 刻意排在"建类加载器图"之前:发现只用 java.util.zip 读 jar,不需要类加载器;
        // 而 mod jar 必须成为**游戏层**类加载器的根,否则 mod 的类根本加载不到.
        // 顺序颠倒的话,要么 mod 类找不到,要么得事后往已冻结的 ClassPathIndex 里插根.
        LoadingModList modList = discover(args);
        // 让已创建的扩展上下文看到发现结果(onLoaderInit 时它还是空的).
        discoveredMods.set(modList.modFiles());
        Log.info("Mod discovery: {} mod file(s)", modList.size());
        for (String modId : modList.modIds()) {
            Log.info("  found mod '{}' -> {}", modId,
                    modList.byModId(modId).orElseThrow().getFilePath().getFileName());
        }

        // 3. 三层类加载器图
        // 游戏类路径由启动器通过 gameClasspath 显式给出,而不是用 java.class.path:
        // 后者混进了加载器自己的 jar,会让游戏层加载器重复定义我们的类.
        PipelineProbe pipelineProbe = new PipelineProbe();

        // 父加载器 = **系统类加载器**,不是平台类加载器.
        // 这是为 Mixin 必须做的选择:混入类的注入处理器签名里含
        // org.spongepowered.asm.mixin.injection.callback.CallbackInfo,
        // 该类型必须与 Mixin 本体,以及我们的平台服务(MultiLoaderMixinService)
        // 是**同一份类定义**.若父是平台加载器,游戏层根本看不见 Mixin
        // (Mixin 在 app 层),目标类校验时会 NoClassDefFoundError: CallbackInfo.
        // 以系统加载器为父**不会**导致重复定义:我们的模块不在游戏层的根里,
        // 只能通过父委派解析,于是全局只有一份.同理 ASM 也只有一份,
        // Mixin 与我们的转换管道共用同一份 ASM -- 这点很重要,
        // 两份 ASM 会让 ClassNode 类型不兼容.
        ClassLoaderGraph.Builder graphBuilder = ClassLoaderGraph.builder(
                        ClassLoader.getSystemClassLoader(), probe.namespace())
                .addClasspathRoots(args.existingClasspath())
                .addProcessor(pipelineProbe);

        int modRoots = 0;
        for (IModFile modFile : modList.modFiles()) {
            for (Path root : modFile.getClasspathRoots()) {
                graphBuilder.addClasspathRoot(root, ClassPathIndex.Scope.MOD);
                modRoots++;
            }
        }

        // 标签刻意不用 "platform=":这个槽位现在装的是**加载器层的父加载器**.
        // 原先是平台加载器,后来为 Mixin 的类标识统一(注入处理器签名里的
        // CallbackInfo 等类型必须与 Mixin 本体,以及我们的平台服务是同一份定义)
        // 改成了系统类加载器.于是它与 loader 槽位指向同一个实例.
        // 这不影响功能(游戏类仍由 game 层 child-first 定义),但旧标签会打出
        // "platform=app loader=app" 这种自相矛盾的两行--诊断信息必须先自洽.
        // 如实报成 "loader / loaderParent" 才是它真正的形状:
        // 加载器层直接架在 app 层之上,platform 层是 app 的父.
        ClassLoaderGraph graph = graphBuilder.build();

        // 3.5 扩展通知:游戏层已就绪,Mod 已全部发现
        // 契约:此刻 gameLoader() 可用,modFiles() 已填充,生命周期尚未开始.
        // 必须排在 graphBuilder.build() **之后**:IGameContext.gameLoader() 要给出
        // 真正的游戏层加载器.提前拿到的话,扩展会把类定义到错误的层上
        // (例如定义进加载器层,于是与游戏层各有一份同名类--
        // 正是本项目反复警惕的类标识问题).
        // 注:本行会打在下面的 "ClassLoaderGraph built:" 之前.不是笔误--
        // 扩展确实是在图建成后**立刻**被通知的,而那条日志只是紧接着的汇报.
        GameContextImpl gameContext = new GameContextImpl(graph.gameLoader());
        // 把入口点查询交给扩展(桥接层不得依赖核心模块,所以只能走这条反向通道).
        // 传 Supplier 而不是 state 本身:这一刻 state 还没创建(见下方 new BootstrapState),
        // 而注册必须注册进**这个** gameContext 实例(bridge 在 onGameBoot 捕获的就是它).
        gameContext.registerService(dev.multiloader.api.lifecycle.IEntrypointQuery.class,
                new EntrypointQueryImpl(LoaderBootstrap::stateOrNull));
        for (MultiLoaderExtension extension : ServiceRegistry.loadAll(MultiLoaderExtension.class)) {
            try {
                extension.onGameBoot(gameContext);
            } catch (RuntimeException e) {
                Log.error("Extension '" + extension.name() + "' failed in onGameBoot", e);
            }
        }

        Log.info("ClassLoaderGraph built: loader={} loaderParent={} game={} (gameRoots={} modRoots={})",
                graph.loaderLoader().getName(),
                graph.platformLoader().getName(),
                graph.gameLoader().loaderName(),
                args.existingClasspath().size(), modRoots);

        // 4. Mixin 配置聚合(按侧别过滤)
        MixinService mixinService = new MixinService();
        mixinService.init(graph.gameLoader(), probe.namespace());

        MixinConfigManager.CollectionResult collected = MixinConfigManager.collect(modList, args.side());
        mixinService.addConfigurations(collected.configs());

        MixinService.MixinAudit audit = mixinService.audit();
        Log.info("Mixin audit: {} config(s), {} mixin class(es), {} conflict(s), fatal={}",
                audit.configCount(), audit.declarationCount(), audit.conflicts().size(),
                audit.hasFatalConflicts());
        if (collected.hasProblems()) {
            collected.problems().forEach(p -> Log.warn("  {}", p));
        }
        audit.conflicts().forEach(c -> Log.warn("  mixin conflict: {}", c));

        // 5. 启动 Mixin 并接进转换管道
        // 没有 mixin 配置时**不**启动:Mixin 一启动就会注册一堆全局状态与钩子,
        // 对没有任何 mixin 的运行来说纯是额外风险面.
        if (!collected.configs().isEmpty()) {
            mixinService.bootstrap(args.side());
            graph.gameLoader().addProcessor(mixinService.transformerProcessor());
            Log.info("Mixin transformer wired into pipeline: {}", graph.gameLoader().describePipeline());
        }

        BootstrapState state = new BootstrapState(args, probe, graph, modList, mixinService,
                collected.configs(), pipelineProbe);
        STATE.set(state);

        Log.info("Loader layer initialized. Handing over to {} via dev.multiloader.launcher.Main",
                args.gameMain());
        return state;
    }

/**
     * 报告转换管道的活动证据.
     * <p>必须在游戏主类**已经加载之后**调用:只有当
     * {@code net.minecraft.server.Main} 真的经由管道走过一遍,
     * "TransformingClassLoader 接管了主类加载"才算被证明,
     * 而不是仅仅"我们创建了一个类加载器".
*/
    public static void logPipelineSummary() {
        BootstrapState state = requireState();
        PipelineProbe probe = state.pipelineProbe();
        probe.logSummary();

        String gameMainInternal = state.args().gameMain().replace('.', '/');
        boolean sawMain = probe.sawClass(gameMainInternal);

        Log.info("Pipeline evidence: gameMain={} routedThroughPipeline={}", gameMainInternal, sawMain);

        // 针对性检查:Mixin 的目标类是否走了管道.
        // "mixin 没生效"最常见的两种原因 -- 目标类压根没经过转换管道,
        // 或者经过了但 Mixin 判断不适用 -- 这条日志能一眼分开.
        for (String target : List.of("net/minecraft/server/MinecraftServer",
                "net/minecraft/server/dedicated/DedicatedServer")) {
            Log.info("  pipeline saw {} = {}", target, probe.sawClass(target));
        }
        // Mixin 的活动统计:区分"Mixin 被调用过但没找到目标"和"Mixin 根本没被调用".
        // 这两个失败模式的排查方向完全不同,必须能一眼分开.
        // 同样标注为部分读数:此刻游戏主类刚加载完,游戏自身还没开始加载类.
        // transformed=0 在这里是**正常**的,判据是运行期那条
        // "Mixin TRANSFORMED ... running total transformed=N".
        Log.info("Mixin transformer activity (partial — before the game loads its own classes): {}",
                state.mixinService().describeTransformerActivity());
        if (!sawMain) {
            // 这不该发生:主类是我们自己 Class.forName 拉起来的,必然走 findClass.
            // 报出来而不是静默,因为一旦成立就说明管道对游戏类是失效的.
            Log.warn("Game main class did NOT pass through the transform pipeline — "
                    + "pipeline is not effective for game classes");
        }
    }

    // 游戏层入口(由 launcher.Main 调用)

/**
     * 在游戏层类加载器里分发某个生命周期阶段的入口点.
     * <p>为什么用**方法名反射**而不是 instanceof {@code ModInitializer}:
     * 那样会让加载器编译期依赖 fabric-loader.真实 mod 的方法签名是稳定的公共契约,
     * 按名字调用既能跑真实 mod,又不把某个加载器的 API 焊进核心.
     * @return 实际成功调用的入口点数量
*/
    public static int dispatch(LifecyclePhase phase) {
        BootstrapState state = requireState();
        List<PendingEntrypoint> pending = collectEntrypoints(state, phase);

        int invoked = 0;
        for (PendingEntrypoint entry : pending) {
            EntrypointRef ref = entry.ref();
            try {
                // 登记放在**调用点**:invokeEntrypoint 内部有两条路径(服务注入 / 无参),
                // 在此处登记则两条自动都被覆盖;写进它内部就得写两次,而"两处各写一遍"
                // 正是某天只改一处的来源.
                DispatchedEntrypoints.record(ref, invokeEntrypoint(entry, state.gameLoader()));
                invoked++;
                Log.info("  [{}] {} -> {} OK", ref.modId(), phase, ref.className());
            } catch (ReflectiveOperationException | LinkageError e) {
                Log.error("  [" + ref.modId() + "] " + phase + " " + ref.className() + " FAILED", e);
            }
        }
        Log.info("Dispatched {} entrypoint(s) at {} (of {} declared)", invoked, phase, pending.size());
        return invoked;
    }

/**
     * 一条待调用的入口点,连同它所属的 mod 文件.
     * <p><b>为什么必须成对传</b>:服务注入要调
     * {@code IEntrypointServiceProvider.provide(fqn, file)},而 {@code file} 是
     * "按 mod 区分状态"的依据(例如每个 mod 的 {@code ModContainer}).
     * 早先 {@code collectEntrypoints} 把 ref 拍平成一个列表,file 就此丢失 --
     * 那样即使 provider 拿到了 FQN,也无法判断该给哪个 mod 造实例,
     * 只能退化成"全局唯一实例",而 {@code ModContainer} 语义上必须按 mod 区分.
*/
    record PendingEntrypoint(EntrypointRef ref, IModFile file) {
    }

/**
     * 收集**全部阶段**,且在当前侧别生效的入口点(连同所属 mod 文件).
     * <p><b>这是唯一的枚举与过滤实现.</b> 阶段分发({@link #collectEntrypoints})与
     * 按 key 查询({@code EntrypointQueryImpl})都从这里取 -- 若各写一份,
     * 后果是**同一个环境判定被维护在两处**,某天只改了一处,
     * 两条路径就会对"哪些入口点算数"给出不同答案,而调用方无从判断哪个对.
*/
    static List<PendingEntrypoint> collectAll(BootstrapState state) {
        boolean clientSide = state.args().side().isClient();
        List<IEntrypointProvider> providers = ServiceRegistry.loadAll(IEntrypointProvider.class);

        if (providers.isEmpty()) {
            Log.warn("No IEntrypointProvider discovered — adapters are probably missing from the runtime classpath");
        }

        List<PendingEntrypoint> refs = new ArrayList<>();
        for (IModFile modFile : state.modList().modFiles()) {
            for (IEntrypointProvider provider : providers) {
                Map<LifecyclePhase, List<EntrypointRef>> byPhase = provider.getEntrypoints(modFile);
                for (List<EntrypointRef> forPhase : byPhase.values()) {
                    for (EntrypointRef ref : forPhase) {
                        if (ref.environment().appliesTo(clientSide)) {
                            refs.add(new PendingEntrypoint(ref, modFile));
                        }
                    }
                }
            }
        }
        return refs;
    }

/**
     * 收集指定阶段,且在当前侧别生效的入口点(连同所属 mod 文件).
     * <p>只做"按阶段筛",枚举与环境过滤全部复用 {@link #collectAll}.
*/
    private static List<PendingEntrypoint> collectEntrypoints(BootstrapState state,
            LifecyclePhase phase) {
        return collectAll(state).stream()
                .filter(pending -> pending.ref().phase() == phase)
                .toList();
    }

    private static Object invokeEntrypoint(PendingEntrypoint entry, ClassLoader gameLoader)
            throws ReflectiveOperationException {
        EntrypointRef ref = entry.ref();

        // 注入形态**先于**无参查询分流.这一句是顺序上的关键:
        // 带服务参数的 @Mod 类**没有无参构造器**,若照旧先查 getDeclaredConstructor(),
        // 会以 NoSuchMethodException: <init>() 失败 -- 错误信息指向"缺少无参构造器",
        // 而真因是"需要服务注入".指向错因比不给信息更贵:它会让人去给 mod 加无参构造器.
        if (ref.requiresServiceInjection()) {
            return invokeServiceConstructor(ref, entry.file(), gameLoader);
        }

        // 用 Class.forName(name, true, gameLoader) 而不是 gameLoader.loadClass():
        // 显式指定初始化,避免"类被加载但静态初始化没跑"这种半成品状态.
        Class<?> type = Class.forName(ref.className(), true, gameLoader);

        // 实例化用 getDeclaredConstructor() 而非 getConstructor():
        // mod 入口点类常是包私有或非 public 的,public-only 查询会漏掉它们.
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();

        switch (ref.kind()) {
            case NO_ARG_METHOD -> {
                // getMethod 而非 getDeclaredMethod:入口方法可能声明在接口上
                // (真实 Fabric mod 就是 implements ModInitializer),getMethod 能找到.
                var method = type.getMethod(ref.methodName());
                method.invoke(instance);
            }
            case NO_ARG_CONSTRUCTOR -> {
                // 构造器本身就是入口点,实例化已完成
            }
            default -> throw new IllegalStateException(
                    "unreachable: " + ref.kind() + " should have been handled by the injection path");
        }
        // 返回实例供分发器登记进缓存 -- mod 主动查询时复用同一个,不再造第二份.
        return instance;
    }

/**
     * 按形参类型解析出实例,再反射调用构造器.
     * <p><b>形参类型必须用 {@code gameLoader} 解析</b>:若用核心层的加载器去解析
     * {@code net.neoforged.bus.api.IEventBus},会得到**另一个类加载器里的同名类**,
     * 与 mod 类引用的那个不是同一个 ->{@code getDeclaredConstructor} 抛
     * {@code NoSuchMethodException}.这与本项目已踩过的 {@code CallbackInfo}
     * 类标识问题是同一个坑.
*/
    private static Object invokeServiceConstructor(EntrypointRef ref, IModFile file,
            ClassLoader gameLoader) throws ReflectiveOperationException {
        List<String> parameterFqns = ref.constructorParamFQNs();
        List<IEntrypointServiceProvider> providers =
                ServiceRegistry.loadAll(IEntrypointServiceProvider.class);

        // 参数解析与 provider 查找抽成纯函数,好让它能被独立测试
        // (不需要真实类加载器,不需要真实 mod 类).
        Object[] arguments = resolveArguments(ref, file, providers);

        Class<?>[] parameterTypes = new Class<?>[parameterFqns.size()];
        for (int i = 0; i < parameterFqns.size(); i++) {
            // 初始化标志用 false:这里只需要 Class 令牌去查构造器,
            // 提前触发注入类型(可能来自桥接产物)的静态初始化没有必要.
            parameterTypes[i] = Class.forName(parameterFqns.get(i), false, gameLoader);
        }

        Class<?> type = Class.forName(ref.className(), true, gameLoader);
        var constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

/**
     * 纯函数:按"形参 FQN 顺序 ->provider ->实例",返回可直接传给构造器的参数数组.
     * <p><b>为什么单独抽出来</b>:这段逻辑有两条值得测的性质,而它们都不该
     * 依赖真实类加载器或真实 mod 类(那会让测试退化成端到端):
     * <ol>
     *   <li><b>顺序</b>:参数必须严格按 FQN 顺序排列 -- 错位会拿去一个
     *       "参数类型都对但位置不对"的构造器,要么抛 NoSuchMethodException
     *       (指向错因),要么更糟:命中另一个同参数集的构造器</li>
     *   <li><b>失败可见</b>:找不到 provider 必须抛异常且信息可诊断,
     *       不能静默塞 null</li>
     * </ol>
     * <p>包可见(而非 private)就是为了让测试能直接调它.
*/
    static Object[] resolveArguments(EntrypointRef ref, IModFile file,
            List<IEntrypointServiceProvider> providers) {
        List<String> parameterFqns = ref.constructorParamFQNs();
        Object[] arguments = new Object[parameterFqns.size()];

        for (int i = 0; i < parameterFqns.size(); i++) {
            String fqn = parameterFqns.get(i);
            IEntrypointServiceProvider provider = findProvider(providers, fqn);
            if (provider == null) {
                throw missingProvider(ref, fqn, providers);
            }
            arguments[i] = provider.provide(fqn, file);
        }
        return arguments;
    }

    private static IEntrypointServiceProvider findProvider(
            List<IEntrypointServiceProvider> providers, String fqn) {
        for (IEntrypointServiceProvider provider : providers) {
            if (provider.canProvide(fqn)) {
                return provider;
            }
        }
        return null;
    }

/**
     * 构造"找不到 provider"的异常.
     * <p><b>不静默跳过</b>:跳过会让 mod 完全不初始化,而日志里没有任何异常 --
     * 表现为"mod 装了但什么都不做",归因方向会指向 mod 自己而不是桥接层.
     * <p>信息里列出每个已注册 provider 的**名字与能力清单**:只说"找不到"
     * 无法区分"缺 provider"与"provider 在场但漏了某个 FQN",而两者修法完全不同.
*/
    private static UnsupportedOperationException missingProvider(EntrypointRef ref, String fqn,
            List<IEntrypointServiceProvider> providers) {
        List<String> described = new ArrayList<>();
        for (IEntrypointServiceProvider provider : providers) {
            described.add(provider.name() + " " + provider.providedFqns());
        }
        String message = "无法为 @Mod 类 " + ref.className() + "（mod " + ref.modId()
                + "）提供构造器参数 " + fqn + "：没有任何 IEntrypointServiceProvider 能提供它。"
                + "已注册的 provider: " + (described.isEmpty() ? "（无）" : described)
                + " 若为（无），说明桥接层不在运行时类路径上；"
                + "否则是 provider 的能力清单里没有这个 FQN。";
        Log.error("{}", message);
        return new UnsupportedOperationException(message);
    }

    public static BootstrapState requireState() {
        BootstrapState state = STATE.get();
        if (state == null) {
            throw new IllegalStateException(
                    "MultiLoader loader layer is not initialized — was the JVM started with -javaagent?");
        }
        return state;
    }

    // 内部

    private static LoadingModList discover(StartupArgs args) {
        Path modsDir = args.modsDir();
        if (!Files.isDirectory(modsDir)) {
            Log.info("Mods directory does not exist, treating as zero mods: {}", modsDir);
            return LoadingModList.of(List.of());
        }

        ModDiscoverer discoverer = ModDiscoverer.fromServices(
                List.<IModFileCandidateLocator>of(new DirectoryModCandidateLocator(modsDir)));
        ModDiscoverer.DiscoveryResult result = discoverer.run();

        for (String problem : result.problems()) {
            Log.warn("  discovery: {}", problem);
        }
        return LoadingModList.of(result.modFiles());
    }

    private static void applyNamespacePolicy(VersionDetector.ProbeResult probe) {
        if (probe.namespace() == GameNamespace.OFFICIAL) {
            return;
        }
        // 探测失败或确为混淆环境:核心层没有重映射能力,必须说清楚而不是静默继续.
        Log.warn("Namespace is {} — the legacy compatibility layer is NOT implemented in this build.",
                probe.namespace());
        Log.warn("Mods whose bytecode targets obfuscated names will fail to load. "
                + "Core-layer pipeline will still run (no remapping stage).");
    }

    private static void validate(StartupArgs args) {
        if (!args.hasGameClasspath()) {
            throw new IllegalArgumentException(
                    "no game classpath: pass gameClasspath=<a,b,c> or gameJar=<path> in the agent arguments "
                            + "(or launch with a JVM classpath)");
        }
        if (args.gameRoot() == null) {
            throw new IllegalArgumentException("gameRoot is required");
        }
        for (Path entry : args.existingClasspath()) {
            if (!Files.exists(entry)) {
                Log.debug("classpath entry does not exist (will be skipped): {}", entry);
            }
        }
    }

    public static String agentVersion() {
        String version = LoaderBootstrap.class.getPackage().getImplementationVersion();
        return version == null ? "0.1.0-SNAPSHOT (dev)" : version;
    }

    public static boolean isInitialized() {
        return STATE.get() != null;
    }

    public static BootstrapState stateOrNull() {
        return STATE.get();
    }
}
