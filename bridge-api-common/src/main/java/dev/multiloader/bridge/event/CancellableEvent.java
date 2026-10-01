package dev.multiloader.bridge.event;

/**
 * 可取消的统一事件.
 * <p>与 {@link ModEvent} 分开的理由:可取消性是**部分事件**的属性.
 * 把它放进基接口会让所有事件(包括服务端启动这种语义上不可取消的)都实现
 * 一个无意义的 {@code setCancelled},调用方也无从判断某个事件到底能不能被取消.
 * <p>取消的**语义**(取消后发生什么)由各事件自己定义并在 javadoc 里写明 
 * 这一点必须落在具体事件上:方块破坏被取消 = 方块不消失,
 * 而"玩家加入被取消"是不成立的,所以后者不会实现本接口.
*/
public interface CancellableEvent extends ModEvent {

    boolean isCancelled();

    void setCancelled(boolean cancelled);
}
