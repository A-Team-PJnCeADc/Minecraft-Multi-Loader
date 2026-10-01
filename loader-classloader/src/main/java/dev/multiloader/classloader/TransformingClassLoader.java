package dev.multiloader.classloader;

import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.api.transform.IClassHierarchy;
import dev.multiloader.api.transform.TransformException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 游戏层类加载器.所有 Minecraft 类与所有 mod 类都由它加载.
 * <p>管道约定(26.3 未混淆,**不含重映射阶段**):
 * <pre>
 *   Mixin -> AccessTransformer -> 其他
 * </pre>
 * 旧版兼容层将来会通过 {@link #addProcessor} 注册一个 order 为负的处理器,
 * 从而自然排到 Mixin 之前,形成
 * <pre>
 *   重映射 -> Mixin -> AccessTransformer -> 其他
 * </pre>
 * 核心层不需要知道那个处理器是什么.
 * <p>Mixin 逃逸口(两个,缺一不可):
 * <ul>
 *   <li>{@link #getClassBytes(String)}(继承自 {@link ModuleClassLoader}):
 *       读原始字节码,不转换不定义</li>
 *   <li>{@link #loadClassNoTransform(String)}:定义类但跳过管道,
 *       用于打破"转换 ->加载 ->转换"的循环</li>
 * </ul>
*/
public final class TransformingClassLoader extends ModuleClassLoader {

    private final List<ClassProcessor> registered = new CopyOnWriteArrayList<>();
    private final AsmClassHierarchy hierarchy;

    private volatile ClassProcessorSet active = ClassProcessorSet.empty();

    public TransformingClassLoader(String loaderName,
                                   ClassPathIndex index,
                                   Set<String> childFirstPrefixes,
                                   ClassLoader parent) {
        super(loaderName, index, childFirstPrefixes, parent);
        this.hierarchy = new AsmClassHierarchy(index);
    }

/**
     * 注册一个处理器.
     * <p>必须在首次类加载之前完成.注册会重建排序集,因此顺序变化是线程可见的
     * (volatile 写),但运行中追加处理器仍不受支持,管道一旦跑起来就应冻结.
*/
    public void addProcessor(ClassProcessor processor) {
        registered.add(processor);
        this.active = ClassProcessorSet.build(registered);
    }

/** 当前冻结的管道. */
    public ClassProcessorSet processors() {
        return active;
    }

/** 只读层级查询,供处理器与 Mixin 使用. */
    public IClassHierarchy hierarchy() {
        return hierarchy;
    }

    @Override
    protected byte[] transformClassBytes(String className, byte[] raw) {
        ClassProcessorSet pipeline = active;
        if (pipeline.isEmpty()) {
            return raw;
        }

        String internalName = className.replace('.', '/');
        Set<String> inProgress = transforming.get();
        if (!inProgress.add(internalName)) {
            // 递归:转换过程中又请求加载同一个类,直接给原始字节打破循环
            return raw;
        }
        try {
            return runPipeline(pipeline, internalName, raw);
        } catch (TransformException e) {
            // findClass 不能抛受检异常,而转换失败必须致命,包一层带出类加载边界
            throw new ClassTransformException("Transformation failed for " + internalName, e);
        } finally {
            inProgress.remove(internalName);
        }
    }

/**
     * 直接对给定字节码跑管道,不定义类.
     * <p>供测试与"只想看看转换结果"的诊断路径使用.
*/
    public byte[] transformClass(String internalName, byte[] input) throws TransformException {
        return runPipeline(active, internalName, input);
    }

    private byte[] runPipeline(ClassProcessorSet pipeline, String internalName, byte[] input)
            throws TransformException {
        if (pipeline.isEmpty()) {
            return input;
        }
        ClassContextImpl ctx = new ClassContextImpl(internalName, input, this, hierarchy);
        try {
            return pipeline.processAll(ctx);
        } catch (TransformException e) {
            throw new TransformException(
                    "Pipeline [%s] failed on %s".formatted(pipeline.describe(), internalName), e);
        } catch (RuntimeException e) {
            throw new TransformException(
                    "Pipeline [%s] threw on %s".formatted(pipeline.describe(), internalName), e);
        }
    }

/**
     * 加载类但**跳过转换管道**.
     * <p>Mixin 的第二个逃逸口:它需要在 apply 之前拿到"未注入"的目标类,
     * 否则会把自己的注入结果再注入一次.
*/
    public Class<?> loadClassNoTransform(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            String internalName = name.replace('.', '/');
            byte[] raw = getClassBytes(internalName);
            if (raw == null) {
                throw new ClassNotFoundException(name + " (not found on " + loaderName() + " classpath)");
            }
            return defineClass(name, raw, 0, raw.length);
        }
    }

/** 供诊断:管道当前的排序描述. */
    public String describePipeline() {
        return active.isEmpty() ? "<empty>" : active.describe();
    }
}
