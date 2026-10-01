package dev.multiloader.agent;

import dev.multiloader.common.Log;

import java.lang.instrument.Instrumentation;

/**
 * javaagent 入口.
 * <p>Q1 决策 = 方案 (a):启动器把 launch profile 的 main class 换成
 * {@code dev.multiloader.launcher.Main}.premain 在这里完成**加载器层**初始化
 * (探测,类加载器图,mod 发现,Mixin 启动),此时不加载任何游戏类.
*/
public final class MultiLoaderAgent {

    private MultiLoaderAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation, "premain");
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation, "agentmain");
    }

    private static void start(String rawAgentArgs, Instrumentation instrumentation, String mode) {
        Log.info("MultiLoader agent starting ({})", mode);

        try {
            LoaderBootstrap.BootstrapState state = LoaderBootstrap.bootstrap(rawAgentArgs, instrumentation);
            Log.info("MultiLoader agent ready ({}): {}", mode, state.probe().summaryLine());
        } catch (Throwable failure) {
            Log.error("MultiLoader agent initialization failed", failure);

            if (tolerateFailure(rawAgentArgs)) {
                Log.warn("tolerateFailure=true — continuing, the game will run WITHOUT any mods applied");
                return;
            }

            // 刻意用 System.exit 而不是把异常抛出 premain.
            // 从 premain 抛异常会让 JVM 走 "processing of -javaagent failed" 路径,
            // 在本机 JDK 25 上直接触发 libinstrument 的原生断言
            // (jni_FatalError / processJavaStart failed),进程以 SIGABRT(134) 结束,
            // 我们精心写的那条错误信息被淹没在原生崩溃输出里.
            // 自己 exit 出去,退出码是干净的 1,日志是完整的.
            Log.error("MultiLoader failed to initialize; aborting instead of running a partially "
                    + "initialized loader. Pass tolerateFailure=true to override.");
            System.exit(1);
        }
    }

    private static boolean tolerateFailure(String rawAgentArgs) {
        try {
            return StartupArgs.parse(rawAgentArgs).tolerateFailure();
        } catch (RuntimeException e) {
            // 参数本身解析不了时报错已经不重要了  反正要退出
            return false;
        }
    }
}
