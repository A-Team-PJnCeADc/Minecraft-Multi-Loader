package dev.multiloader.bridge.event;

/**
 * 统一事件总线.
 * <p><b>本接口只做注册,不做分发.</b> 分发({@code post})不在本期范围内:
 * 它需要完整的事件类型注册与分发机制,而 2b 的目标只是让 {@code @Mod} 类跑起来.
 * 见 {@code docs/02-bridge-design.md} §2.
 * <p>为什么统一层也需要一个"总线"而不是只给纯函数式注册:
 * 监听器要能被**注销**(mod 卸载,或监听器持有玩家引用需要释放).
 * 只提供注册不提供注销,会让长期运行的服务端累积泄漏.
 * <p><b>实施约束(重要)</b>:桥接实现必须在 {@link #register} 时对监听器做
 * 一次方法签名反射,据参数类型决定路由到统一层还是原生层 
 * 不能等到事件发生时才判断,那时已不知道该按哪种调用约定解释它.
*/
public interface EventBus {

/**
     * 注册一个监听器.
     * <p>监听器的形态由**加载器侧**约定(例如 NeoForge 的 {@code @SubscribeEvent}
     * 方法).统一层只约定"注册进去,需要时能注销",不约定方法签名 
     * 约定签名就等于把某一加载器的调用约定写进了统一层.
     * @throws UnsupportedOperationException 当监听器的事件类型属于本期不支持的类别
     *                                       (见设计文档 §3 的不映射清单).
     *                                       刻意抛异常而不是忽略:静默丢弃会让 mod
     *                                       行为异常且无从诊断.
*/
    void register(Object listener);

/**
     * 注销监听器.
     * @return 是否确实注销了(未注册过时返回 false,便于诊断重复注销)
*/
    boolean unregister(Object listener);
}
