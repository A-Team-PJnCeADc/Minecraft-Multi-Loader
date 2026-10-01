package dev.multiloader.agent;

import dev.multiloader.api.transform.ClassContext;
import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.api.transform.ProcessPhase;
import dev.multiloader.api.transform.ProcessPhases;
import dev.multiloader.common.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管线探针:一个**语义上的 no-op** 处理器,但把管道活动记录成证据.
 * <p>为什么需要它:S6a 要回答的问题是"转换管道是否真的在真实游戏类上跑起来了".
 * 这个问题光看日志没用必须让管道自己报出"我处理过哪些类".
 * 它原样返回输入字节(no-op),因此如果游戏还能正常启动,
 * 就同时证明了"管道被调用"和"no-op 不破坏字节码"两件事.
 * <p>刻意只处理 {@code net/minecraft/} 前缀:游戏类路径上有大量第三方库,
 * 全量记录会把真正关心的信号淹掉.
*/
final class PipelineProbe implements ClassProcessor {

    private final Set<String> seenClasses = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> sizes = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicInteger invocationCount = new AtomicInteger();

    @Override
    public String name() {
        return "pipeline-probe";
    }

/**
     * 放在管道**最前**({@link ProcessPhases#MIXIN} 之前),
     * 这样记录到的类名是"进入管道时的原始输入",不受后续处理器影响.
*/
    @Override
    public ProcessPhase phase() {
        // 用 PRE_PIPELINE_BASE 本身:探针要看到**最原始**的输入字节.
        // 兼容层(重映射)应当使用 PRE_PIPELINE_BASE 之后的偏移,
        // 这样它看到的输入是"探针记录过的那份",诊断时能对上号.
        return new ProcessPhase("pipeline-probe", ProcessPhases.PRE_PIPELINE_BASE);
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean handles(String className, byte[] input) {
        return className.startsWith("net/minecraft/");
    }

    @Override
    public byte[] process(ClassContext ctx) {
        invocationCount.incrementAndGet();
        if (seenClasses.add(ctx.className())) {
            sizes.put(ctx.className(), ctx.input().length);
        }
        return ctx.input();   // 原样返回：no-op 语义
    }

    int invocationCount() {
        return invocationCount.get();
    }

    Set<String> seenClasses() {
        return Set.copyOf(seenClasses);
    }

/**
     * 游戏主类是否真的经由我们这条管道 这就是"TransformingClassLoader 接管了主类加载"的证据.
*/
    boolean sawClass(String internalName) {
        return seenClasses.contains(internalName);
    }

    void logSummary() {
        // 标注为**部分读数**:这条在游戏主类刚加载后被调用,那时游戏自身还没开始加载类
        // (MinecraftServer 之类都在游戏 main() 里才加载),所以数字会很小.
        // 注意:本探针**不统计转换数**,只统计"调用次数"与"见过的类".
        // 判断 Mixin 是否生效要看运行期那条
        // "Mixin TRANSFORMED ... running total transformed=N".
        // (早先这里错写成"transformed 必然为 0",把探针和 Mixin 的计数器混为一谈.)
        Log.info("Pipeline probe (partial — right after game main class load, before the game "
                        + "loads its own classes): {} invocation(s), {} distinct class(es) seen",
                invocationCount(), seenClasses.size());

        if (Log.isVerbose()) {
            // 这些都是**见过的类及其输入字节数**,不是被转换过的类 
            // 探针是 no-op,它从不改写任何东西.
            sizes.entrySet().stream()
                    .limit(20)
                    .forEach(e -> Log.debug("  saw {} ({} bytes in)", e.getKey(), e.getValue()));
        }
    }
}
