package dev.multiloader.mixin.platform;

import dev.multiloader.common.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把**未转换的**原始字节码喂给 Mixin.
 * <p>这里是整条 Mixin 集成里最容易搞错的一处:
 * Mixin 需要的是目标类的**原始**字节码,用来推算注入点.
 * 如果我们把已经过一遍管道的字节给它,就会出现"重复注入"或
 * 注入点被前一轮改动错位  表现为诡异的验证错误,且极难归因.
 * <p>所以本类刻意走 {@code TransformingClassLoader.getClassBytes()}
 * 那是 S4 就预留下来的**不触发转换**的逃逸口,
 * 读的是 {@code ClassPathIndex} 里的原始字节.
*/
final class GameBytecodeProvider implements IClassBytecodeProvider {

/** 已诊断过的类名 ->次数,避免重复刷屏但保留"被问了多次"的信息. */
    private static final ConcurrentHashMap<String, AtomicInteger> DIAGNOSTIC_COUNTS =
            new ConcurrentHashMap<>();

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, false);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, ClassReader.EXPAND_FRAMES);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {

        // 刻意忽略 runTransformers:Mixin 想要"原始类",
        // 而我们的管道里 Mixin 自己就是其中一个处理器  应用管道等于递归调用自己.
        // 平台侧要提供给 Mixin 的永远是未转换视图.
        String internalName = name.replace('.', '/');
        byte[] raw = MultiLoaderMixinService.gameLoader().getClassBytes(internalName);

        if (raw == null) {
            LogDiag.notFound(name, internalName);
            throw new ClassNotFoundException(name + " (not found in the game class path index)");
        }

        ClassNode node = new ClassNode();
        // SKIP_CODE 视调用方给定;默认 EXPAND_FRAMES 由上层调用传入.
        // 不用 SKIP_DEBUG:Mixin 的 @Inject 定位会用到行号/局部变量表.
        new ClassReader(raw).accept(node, readerFlags);

        LogDiag.record(name, internalName, raw.length, readerFlags, node);
        return node;
    }

/**
     * 诊断输出.
     * <p>存在的理由:把 Mixin 的注解读取这条路整个变成可观测的 
     * "Mixin 请求了哪个类,拿到多少字节,节点里到底有没有 @Mixin 注解".
     * 这三件事必须能一眼看到,否则"mixin 静默不生效"就无从下手.
*/
    private static final class LogDiag {

        private LogDiag() {
        }

        static void record(String name, String internalName, int byteLength, int readerFlags,
                ClassNode node) {
            // 只报告与 mixin 有关的请求,避免把整个游戏类路径刷屏.
            // 规则:名字里含 mixin(混入类通常如此),或者是明显异常的情况.
            boolean interesting = isMixinRelated(name);
            if (interesting) {
                Log.info("[bytecode] requested '{}' flags={} -> {} bytes; node.name={} "
                                + "visibleAnnotations={} invisibleAnnotations={}",
                        name, readerFlags, byteLength, node.name,
                        renderAnnotations(node.visibleAnnotations),
                        renderAnnotations(node.invisibleAnnotations));
            }

            // 第二次及以后只记次数:同一个类被反复请求本身也是有价值的信息
            AtomicInteger counter = DIAGNOSTIC_COUNTS.computeIfAbsent(name, k -> new AtomicInteger());
            int seen = counter.incrementAndGet();
            if (interesting && seen > 1) {
                Log.info("[bytecode]   ('{}' requested {} times)", name, seen);
            }
        }

        static void notFound(String name, String internalName) {
            Log.warn("[bytecode] NOT FOUND: requested '{}' (internal '{}') — "
                            + "not present in the game class path index",
                    name, internalName);
        }

        private static boolean isMixinRelated(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("mixin") || lower.contains("testmod");
        }

/**
         * 把注解列表渲染成"描述符 + 原始键值"的形式.
         * <p>直接 {@code toString()} 出来是 {@code AnnotationNode@1a2b3c} 这种废物,
         * 而这里恰恰要看的是 {@code desc} 与 {@code values} 里有没有 {@code targets} 键.
*/
        private static String renderAnnotations(List<AnnotationNode> annotations) {
            if (annotations == null) {
                return "null";
            }
            if (annotations.isEmpty()) {
                return "[]";
            }
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < annotations.size(); i++) {
                AnnotationNode annotation = annotations.get(i);
                if (i > 0) {
                    out.append(", ");
                }
                out.append(annotation.desc);
                if (annotation.values != null) {
                    out.append(" values=").append(annotation.values);
                }
            }
            return out.append(']').toString();
        }
    }
}
