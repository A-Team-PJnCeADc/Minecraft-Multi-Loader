package dev.multiloader.mixin;

import dev.multiloader.api.transform.ClassContext;
import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.api.transform.ProcessPhase;
import dev.multiloader.api.transform.ProcessPhases;
import dev.multiloader.api.transform.TransformException;
import dev.multiloader.common.Log;
import dev.multiloader.mixin.platform.MultiLoaderMixinService;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 Mixin 接进我们自己的转换管道.
 * <p>这一步是"多加载器统一"的关键:Mixin 并不知道我们的管道存在,
 * 它只提供一个 {@code IMixinTransformer}.我们把它包成一个 {@link ClassProcessor}
 * 放在 {@link ProcessPhases#MIXIN} 阶段,于是所有转换都走同一条有序管道
 * 旧版兼容层的重映射将来会插在 MIXIN 之前的阶段,天然保证"先重映射,再注入".
 * <p>{@link #handles} 恒返回 true 是刻意的:只有 Mixin 自己的配置知道哪些类是目标,
 * 我们在这里做任何过滤都可能漏掉真实目标.Mixin 对非目标类的判断很快,
 * 而且它会把非目标类原样返回.
*/
public final class MixinTransformerProcessor implements ClassProcessor {

    private final MultiLoaderMixinService service;
    private final AtomicInteger transformed = new AtomicInteger();
    private final AtomicInteger inspected = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean firstCallLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    public MixinTransformerProcessor(MultiLoaderMixinService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "mixin";
    }

    @Override
    public ProcessPhase phase() {
        return ProcessPhases.MIXIN;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean handles(String className, byte[] input) {
        return true;
    }

    @Override
    public byte[] process(ClassContext ctx) throws TransformException {
        inspected.incrementAndGet();
        logFirstCallDiagnostics(ctx);
        try {
            // 类名形态必须与 Fabric 的 Knot 一致:**点号二分名**(binary name),
            // 不是斜杠内部名.我们的管道内部用斜杠名,所以这里要转回去.
            // 为什么不能图省事传斜杠名:Mixin 会把 name 与它从 @Mixin 注解里
            // 解析出的目标名做匹配,而解析出来的目标是以点号形式参与比较的.
            // 传斜杠名会让匹配静默失败  配置选中,mixin 准备,目标注册全部正常,
            // 唯独 applyMixins 判定"这个类没有 mixin",transformed 恒为 0.
            String binaryName = ctx.className().replace('/', '.');

            byte[] result = service.transformer()
                    .transformClassBytes(binaryName, binaryName, ctx.input());

            // Mixin 的约定:未发生转换时返回原数组(或 null),两者都表示"这个类与我无关"
            if (result == null) {
                return ctx.input();
            }
            if (result != ctx.input()) {
                int total = transformed.incrementAndGet();
                // 在**转换真正发生的那一刻**报出累计值.
                // 不依赖关停钩子汇总:关停期间 java.util.logging 可能已经被 reset,
                // 消息会静默丢失(实测过钩子注册了但一行都没输出).
                // 而"transformed 是否非 0"恰恰是判断 Mixin 是否生效的判据,
                // 必须在事件发生时就被观测到,而不是事后汇总.
                Log.info("Mixin TRANSFORMED {} ({} -> {} bytes) — running total transformed={}",
                        binaryName, ctx.input().length, result.length, total);
            }

            // 目标类的处理结果直接测量.
            // "Mixin 到底有没有改这个类"必须被观测,而不是从别处推断 
            // 这是区分"目标注册失败"与"注册成功但注入点不匹配"的唯一手段.
            if (isMixinTargetOfInterest(ctx.className())) {
                Log.info("  target processed: {} in={}B out={}B changed={}",
                        ctx.className(), ctx.input().length, result.length,
                        result != ctx.input());
            }
            return result;
        } catch (Throwable t) {
            // 包装成受检异常交给管道:转换失败必须致命,
            // 静默放行会让"mixin 没生效"变成运行期的谜题
            throw new TransformException("Mixin transformation failed for " + ctx.className(), t);
        }
    }

/** 实际被改写的类数量(用于验证 Mixin 是否真的生效,而不是仅仅被调用). */
    public int transformedCount() {
        return transformed.get();
    }

/**
     * 只在第一次调用时打一次环境诊断.
     * <p>用途:区分"Mixin 被调用但判断不该转换"和"配置压根没被处理器看到".
     * 前者看环境(phase / side / 已注册配置数),后者看配置集合是否为空.
     * 这两种失败模式的处理方向完全不同,而外部表现都是"没有任何转换发生".
*/
    private void logFirstCallDiagnostics(ClassContext ctx) {
        // 目标类是否真的经过了管道  每次见到都报,因为这是"Mixin 没生效"
        // 最根本的分叉点:目标类没进来(类加载器问题)vs 进来了但 Mixin 不适用(配置问题).
        String name = ctx.className();
        if (isMixinTargetOfInterest(name)) {
            Log.info("Mixin pipeline touched target class: {} ({} bytes)", name, ctx.input().length);
        }

        if (!firstCallLogged.compareAndSet(false, true)) {
            return;
        }
        try {
            var environment = org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment();
            Log.info("Mixin first-call diagnostics: class={} phase={} side={} activeTransformer={} registeredConfigs={}",
                    ctx.className(),
                    environment.getPhase(),
                    environment.getSide(),
                    environment.getActiveTransformer() == null ? "<null>" : "present",
                    org.spongepowered.asm.mixin.Mixins.getConfigs().size());
        } catch (Throwable t) {
            Log.warn("Mixin first-call diagnostics failed", t);
        }
    }

/** 是否是需要重点观测的 Mixin 目标类. */
    private static boolean isMixinTargetOfInterest(String internalName) {
        return internalName.endsWith("/MinecraftServer") || internalName.endsWith("/DedicatedServer");
    }

/** 交给 Mixin 看过的类数量. */
    public int inspectedCount() {
        return inspected.get();
    }

    public String describe() {
        return "inspected=" + inspectedCount() + " transformed=" + transformedCount();
    }
}
