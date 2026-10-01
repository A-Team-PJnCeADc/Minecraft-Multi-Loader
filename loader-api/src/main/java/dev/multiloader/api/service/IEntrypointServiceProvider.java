package dev.multiloader.api.service;

import dev.multiloader.api.locating.IModFile;

import java.util.Set;

/**
 * 为 {@code @Mod} 类构造器形参提供实例的 SPI.
 * <p><b>为什么需要 SPI 而不是让核心层直接 new</b>:参数类型({@code IEventBus},
 * {@code ModContainer}...)是**加载器专有**的类型.核心层不能依赖任何加载器产物
 * (INV-2),否则 {@code net.neoforged.*} 会泄漏进核心链路.
 * 所以核心层只认"某个 FQN 需要实例",由桥接层经 ServiceLoader 反向注册能力.
 * 这与 {@code MultiLoaderExtension} 是同一套思路.
 * <p><b>为什么 {@code canProvide} 与 {@code provide} 分开</b>:调度器要能"先问能不能"
 * 再决定调用,而不是靠 {@code provide} 抛异常来探测.用异常做流程控制会让
 * "真的构建失败了"与"这里没有这个服务"混在同一条路径上  前者要报错中止,
 * 后者要继续找下一个 provider.
*/
public interface IEntrypointServiceProvider {

/** 用于诊断:哪个桥接提供了这些能力.会在"找不到 provider"的错误信息里列出. */
    String name();

/**
     * 本 provider 能提供的全部构造器形参类型(点号全限定名).
     * <p><b>为什么要有这个方法,而不是只留 {@code canProvide}</b>:
     * 报错信息必须能列出"已注册 provider 各自能提供什么".只用
     * {@code canProvide} 的话,我们只能逐个猜测 FQN  而"猜哪些 FQN 去问"
     * 正是不知道的事.缺了这份清单,"找不到 provider"与
     * "provider 在场但漏了某个 FQN"两种情况在日志里长得一样,
     * 而两者的修法完全不同.
     * <p>返回集合应为**不可变**的(调用方可能长期持有).
*/
    Set<String> providedFqns();

/**
     * 能否为这个构造器形参类型提供服务.
     * <p>按**全限定名**判定(点号分隔,与 {@code Class#getName()} 一致).
     * 不用 {@code Class} 对象:那要求调用方先解析类型,而"能不能提供"正是
     * 在解析之前就要回答的问题.
     * <p>默认委托给 {@link #providedFqns()}.实现若需要更复杂的判定
     * (例如按 mod 区分能力)可以覆写,但**必须与 {@code providedFqns()} 保持一致** 
     * 否则报错清单会与实际判定脱节,那正是最难查的一类不一致.
*/
    default boolean canProvide(String parameterFqn) {
        return providedFqns().contains(parameterFqn);
    }

/**
     * 提供实例.
     * <p><b>必须按 {@code (modId, parameterFqn)} 缓存并返回同一实例</b>:
     * 同一个 mod 的 {@code @Mod} 构造器注入的 bus 与
     * {@code container.getEventBus()} 返回的必须是同一个对象,
     * 否则 mod 注册的监听器会进到一个没人用的总线里 
     * 表现为"注册成功但永不触发",是极难诊断的失效形态.
     * <p>同一个 {@code parameterFqn} 在不同 mod 间是否共享实例由实现决定:
     * 全局 game bus 就是跨 mod 共享的(NeoForge 的真实语义即如此).
     * @param parameterFqn 形参的全限定名;调用方保证已通过 {@link #canProvide(String)}
     * @param file         所属 mod 文件  需要按 mod 区分状态的实现(如 ModContainer)用它
     * @throws UnsupportedOperationException 声称能提供但构建失败时.
     *         返回 null 会把失败推到调用方,届时它只看到一个 null 参数,
     *         堆栈不指向"哪个 provider 没给出实例".
*/
    Object provide(String parameterFqn, IModFile file);
}
