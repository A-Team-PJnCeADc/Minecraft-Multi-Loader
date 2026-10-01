package dev.multiloader.api.lifecycle;

import dev.multiloader.api.metadata.Environment;

import java.util.List;
import java.util.Objects;

/**
 * 一条入口点声明.
 * <p>核心层只认这个类型,不认任何加载器的接口--这正是为什么
 * {@code adapt-fabric} 会把 {@code fabric.mod.json} 翻译成本类型,
 * 而不是把 {@code ModInitializer} 泄漏进核心层.
 * @param modId                  所属 mod
 * @param phase                  在哪个生命周期阶段被调用
 * @param className              入口点类的全限定名(由**游戏层类加载器**加载)
 * @param kind                   调用契约
 * @param methodName             要调用的无参方法名;仅当 {@link EntrypointKind#requiresMethodName()} 时为非 null
 * @param environment            生效侧别
 * @param constructorParamFQNs   构造器形参的全限定名;**仅**当
 *                               {@link EntrypointKind#CONSTRUCTOR_WITH_SERVICES} 时非空
*/
public record EntrypointRef(String modId,
                            LifecyclePhase phase,
                            String className,
                            EntrypointKind kind,
                            String methodName,
                            Environment environment,
                            List<String> constructorParamFQNs,
                            String sourceKey) {

/**
     * 兼容构造器:{@code sourceKey} 缺省为 {@code null}.
     * <p><b>{@code null} 是有意义的状态,不是"忘了填"</b>:它表示"这个入口点不来自
     * 按 key 声明的入口点表".NeoForge 的 {@code @Mod} 就是这样 -- 它没有 key 概念,
     * 所以那些 Ref 的 sourceKey 恒为 null 是**正确**的,不是遗漏.
     * <p>只有 Fabric 风格的入口点({@code main} / {@code client} / {@code server})
     * 才带非 null 的 sourceKey,由 {@code adapt-fabric} 从
     * {@code FabricModFileExtension.Entrypoint.namespace()} 填入.
*/
    public EntrypointRef(String modId, LifecyclePhase phase, String className, EntrypointKind kind,
                         String methodName, Environment environment,
                         List<String> constructorParamFQNs) {
        this(modId, phase, className, kind, methodName, environment, constructorParamFQNs, null);
    }

    public EntrypointRef {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(constructorParamFQNs, "constructorParamFQNs");

        // kind 与 methodName 的一致性是**结构性**约束,放在构造函数里:
        // 让不一致的 ref 根本无法被造出来,而不是等到调度时才发现.
        if (kind.requiresMethodName()) {
            if (methodName == null || methodName.isBlank()) {
                throw new IllegalArgumentException(
                        kind + " requires a non-blank method name: " + modId + " -> " + className);
            }
        } else if (methodName != null) {
            throw new IllegalArgumentException(
                    kind + " must not carry a method name (got '" + methodName + "'): " + modId + " -> " + className);
        }

        // 同一类约束,用于新字段.必须现在就加:constructorParamFQNs 只在一种 kind 下
        // 有意义,若不强制"其它 kind 必须为空",就会出现一个没人读的字段 --
        // 下一个人会以为它有意义,而调度器根本不看它.
        if (kind == EntrypointKind.CONSTRUCTOR_WITH_SERVICES) {
            if (constructorParamFQNs.isEmpty()) {
                throw new IllegalArgumentException(
                        "CONSTRUCTOR_WITH_SERVICES requires at least one constructor parameter: "
                                + modId + " -> " + className);
            }
        } else if (!constructorParamFQNs.isEmpty()) {
            throw new IllegalArgumentException(
                    kind + " must not carry constructor parameters (got " + constructorParamFQNs + "): "
                            + modId + " -> " + className);
        }
    }

/** Fabric 风格:无参构造 + 调用无参方法(不带 entrypoint key). */
    public static EntrypointRef noArgMethod(String modId, LifecyclePhase phase, String className,
                                            String methodName, Environment environment) {
        return new EntrypointRef(modId, phase, className, EntrypointKind.NO_ARG_METHOD,
                methodName, environment, List.of());
    }

/**
     * Fabric 风格 + 入口点 key:{@code sourceKey} 是 {@code fabric.mod.json} 里
     * entrypoints 段的键({@code main} / {@code client} / {@code server}).
     * <p>重载而不是改签名:既有调用点不需要动(NeoForge 侧与测试都不关心 key).
*/
    public static EntrypointRef noArgMethod(String modId, LifecyclePhase phase, String className,
                                            String methodName, Environment environment,
                                            String sourceKey) {
        return new EntrypointRef(modId, phase, className, EntrypointKind.NO_ARG_METHOD,
                methodName, environment, List.of(), sourceKey);
    }

/** NeoForge 简单风格:实例化即完成. */
    public static EntrypointRef noArgConstructor(String modId, LifecyclePhase phase, String className,
                                                 Environment environment) {
        return new EntrypointRef(modId, phase, className, EntrypointKind.NO_ARG_CONSTRUCTOR,
                null, environment, List.of());
    }

/**
     * NeoForge 注入风格:构造器带服务参数,由 {@code IEntrypointServiceProvider} 提供.
     * <p>与另两个工厂同形(薄封装,校验在紧凑构造器里).**不要**绕过工厂直接用
     * 七参构造器 -- 那样会在"工厂 / 构造器"之外多出一个入口,
     * 而上面那条不变量就多了一个可能被绕过的位置.
     * @param constructorParamFQNs 构造器形参全限定名,顺序必须与构造器签名一致
     *                             (调度器按位置逐个解析,顺序错了会拿去构造器的
     *                             {@code NoSuchMethodException},而那不是真因)
*/
    public static EntrypointRef constructorWithServices(String modId, LifecyclePhase phase,
                                                        String className, List<String> constructorParamFQNs,
                                                        Environment environment) {
        return new EntrypointRef(modId, phase, className, EntrypointKind.CONSTRUCTOR_WITH_SERVICES,
                null, environment, List.copyOf(constructorParamFQNs));
    }

/** 是否需要服务注入. */
    public boolean requiresServiceInjection() {
        return kind == EntrypointKind.CONSTRUCTOR_WITH_SERVICES;
    }
}
