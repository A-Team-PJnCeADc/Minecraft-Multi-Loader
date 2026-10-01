package dev.multiloader.bridge.event;

/**
 * 统一事件的标记接口.
 * <p>只有实现本接口的类型才归**统一事件层**管辖;各加载器的原生事件类型
 * 不实现它,因此两条路径可以由类型判定分开(见 {@code docs/02-bridge-design.md} §1.1).
 * <p>刻意做成**标记**而不是带方法的基类/抽象类:
 * <ul>
 *   <li>统一事件需要携带的信息各不相同(玩家加入要玩家,方块破坏要坐标与方块),
 *       基类只能强加一个所有事件都用不上的形状;</li>
 *   <li>标记接口不占用单一继承名额,事件实现可以同时是别的东西.</li>
 * </ul>
 * <p>可取消性<b>不</b>放在这里,而由 {@link CancellableEvent} 单独表达:
 * "能取消"是部分事件的属性,放进基接口会逼所有事件实现一个无意义的
 * {@code setCancelled}.
*/
public interface ModEvent {
}
