package dev.multiloader.launcher;

import dev.multiloader.agent.LoaderBootstrap;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.common.Log;

/**
 * Q1 决策的落地点:**这个类才是 launch profile 里的主类**.
 * <p>分工:
 * <ul>
 *   <li>{@code MultiLoaderAgent.premain}  在 main 之前完成**加载器层**初始化
 *       (探测,类加载器图,mod 发现,Mixin 配置).此时不加载任何游戏类.</li>
 *   <li>{@code Main}(本类) **游戏层**入口.它把游戏主类放进
 *       {@code TransformingClassLoader} 里加载并反射调用,
 *       从而保证游戏类一定经由我们的转换管道.</li>
 * </ul>
 * <p>为什么不选"保留原主类 + 用 ClassFileTransformer 惰性接管":
 * 那条路要处理"部分游戏类已经被系统类加载器加载过"的竞态,而这个竞态没有确定性解
 * 取决于 JVM 在何时解析了哪些符号.换成显式主类之后,接管是**顺序上先于**任何游戏类加载的.
*/
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        LoaderBootstrap.BootstrapState state = LoaderBootstrap.requireState();
        String gameMain = state.args().gameMain();

        Log.info("launcher.Main: handing over to {}", gameMain);

        // 入口点分发放在游戏主类之前.
        // 这是 S6a 的**刻意选择**:真实加载器把入口点放在各自的生命周期里
        // (Fabric 在 Knot 初始化期间,Forge 在 mod loading 期间),
        // 但那个位置需要游戏类已经部分就绪.S6a 的目标是观测"分发是否被调用"
        // 以及"侧别过滤是否生效",所以先放在最容易确定发生的位置,
        // 等链路稳定后再挪到正确的语义位置.
        if (!state.modList().isEmpty()) {
            // CONSTRUCT 排在 INIT 之前:@Mod 构造器是"加载器自身构造完成,
            // 还没有任何 mod 代码执行"的阶段.放晚了会让构造晚于别的 mod 的
            // 静态初始化,而 mod 的构造器里可能就要注册监听器.
            // 放得更早(类加载图建成之前)则 Class.forName(mod类) 找不到类.
            LoaderBootstrap.dispatch(LifecyclePhase.CONSTRUCT);
            LoaderBootstrap.dispatch(LifecyclePhase.INIT);
            // SIDED_SETUP 也一样先提前调用:它承载 Fabric 的 client / server 命名空间入口点,
            // 正是"命名空间隐含侧别"这条规则的观测点(client 那个必须不出现)
            LoaderBootstrap.dispatch(LifecyclePhase.SIDED_SETUP);
        }

        ClassLoader gameLoader = state.gameLoader();
        Class<?> mainType = Class.forName(gameMain, true, gameLoader);

        // 关键检查:主类必须由**我们的**类加载器定义.
        // 如果这里拿到的 classLoader 不是 gameLoader,说明系统类加载器抢先加载了它,
        // 那么整条转换管道对游戏类就是失效的.
        ClassLoader actual = mainType.getClassLoader();
        if (actual != gameLoader) {
            throw new IllegalStateException(
                    "game main class was not loaded by the transforming loader: expected "
                            + gameLoader + " but got " + actual
                            + " — the pipeline would not apply to game classes");
        }
        Log.info("Confirmed: {} is defined by {}", gameMain, actual);

        // 管道活动证据:主类刚被加载,正是观测点
        LoaderBootstrap.logPipelineSummary();

        // 退出时再报一次.此刻游戏类基本都加载过了,这才是
        // "转换管道覆盖了整场游戏运行"的完整证据  只在主类加载后报一次,
        // 拿到的 11 个类并不能说明其余几千个游戏类也走了管道.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                LoaderBootstrap.logPipelineSummary();
            } catch (Throwable ignored) {
                // 关停阶段的异常不该影响退出码,也不该掩盖真正的错误
            }
        }, "multiloader-pipeline-report"));

        mainType.getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
