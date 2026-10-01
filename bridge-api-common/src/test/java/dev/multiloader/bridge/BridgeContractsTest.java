package dev.multiloader.bridge;

import dev.multiloader.bridge.event.CancellableEvent;
import dev.multiloader.bridge.event.EventBus;
import dev.multiloader.bridge.event.ModEvent;
import dev.multiloader.bridge.network.NetworkBridge;
import dev.multiloader.bridge.registry.RegistryBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把设计决定钉成**可执行的契约**.
 * <p>这批测试的特殊之处:它们断言的主要是**不存在的东西**(某个方法没有,
 * 某个层次没被合进去)."某方法不存在"听起来不值得测,但本项目的设计决定
 * 恰恰大量是"不做 X",而"不做 X"在代码里不留痕迹  后人加回 X 时
 * 编译器不会响,测试也不会响,只有设计文档里躺着一条没人读的约定.
 * <p>所以这里把设计文档({@code docs/02-bridge-design.md})里 Q1/Q2/Q3 的
 * 决定变成会失败的断言.失败信息里指向文档,好让改的人先去重读决定,
 * 而不是顺手把测试改掉.
*/
class BridgeContractsTest {

    // Q1:双层设计  统一层不得被加载器语义污染

    @Test
    void modEventIsAMarkerNotABaseClass() throws Exception {
        // 刻意做成标记接口而非带方法的基类:统一事件携带的信息各不相同,
        // 基类只能强加一个所有事件都用不上的形状.
        // 若这里开始出现抽象方法,说明有人把"所有事件的共性"想大了 
        // 那会逼每个具体事件实现无关方法.
        assertTrue(ModEvent.class.isInterface(), "ModEvent 必须是接口（标记接口）");
        List<Method> abstractMethods = Arrays.stream(ModEvent.class.getDeclaredMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .toList();
        assertEquals(List.of(), abstractMethods,
                "ModEvent 应保持纯标记：一旦有抽象方法，就得先回 docs/02-bridge-design.md §1 重新论证");
    }

    @Test
    void cancellableIsSeparateFromModEvent() {
        // "能取消"是部分事件的属性.若合进 ModEvent,
        // 服务端启动这类语义上不可取消的事件也会被迫实现 setCancelled,
        // 而调用方无从判断某个事件到底能不能被取消.
        assertTrue(ModEvent.class.isAssignableFrom(CancellableEvent.class),
                "CancellableEvent 必须也是 ModEvent（否则它进不了统一事件层）");
        assertFalse(CancellableEvent.class.equals(ModEvent.class));
    }

    // Q2:本期只支持注册,post 推迟

    @Test
    void unifiedEventBusDeclaresNoPost() {
        // Q2 的决定是"post 抛 UnsupportedOperationException,留到后续".
        // 注意这个决定**落在桥接实现**上(bridge-neoforge-api),
        // 统一层这里更彻底:连声明都不该有 
        // 一旦统一层声明了 post,任何加载器的桥接都必须实现它,
        // 于是"推迟"就名存实亡.
        List<String> methods = Arrays.stream(EventBus.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        assertEquals(List.of("register", "unregister"), methods.stream().sorted().toList(),
                "统一总线的表面应与 docs/02-bridge-design.md §2 一致；"
                        + "若确实要加 post，请先更新文档再改这里");
    }

    @Test
    void eventBusRegisterPlacesRoutingObligationOnImplementations() {
        // 实施约束的存在证据:register 的参数是 Object(不是某种监听器接口),
        // 因为监听器形态由**加载器侧**约定(NeoForge 的 @SubscribeEvent).
        // 统一层若约定签名,就等于把某一加载器的调用约定写进了统一层.
        // 参数为 Object ->实现必须靠反射判定路由(文档 §1.1).
        List<Class<?>> parameters = Arrays.stream(EventBus.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("register"))
                .map(m -> m.getParameterTypes()[0])
                .toList();
        assertEquals(List.of(Object.class), parameters,
                "register 必须接收 Object：监听器签名是加载器侧的约定");
    }

    // Q3:明确不映射的类别  只放"边界声明",不放会改的方法

    @Test
    void deferredBridgesExposeOnlyAvailabilityQueries() {
        // 注册表与网络的统一语义在加载器间差异大,且属于 §3 明确不映射的类别.
        // 此刻定 register(Identifier, Object) / send(...) 这种签名,
        // 等于把某一加载器的形状当通用形状  改起来要动所有调用方.
        // 所以只留"如何询问可用性":没有方法,就没有错误的方法可依赖.
        for (Class<?> bridge : List.of(RegistryBridge.class, NetworkBridge.class)) {
            List<String> methods = Arrays.stream(bridge.getDeclaredMethods())
                    .map(Method::getName)
                    .sorted()
                    .toList();
            assertEquals(List.of("supported", "unsupportedReason"), methods,
                    bridge.getSimpleName() + " 本期只应有可用性查询，见 docs/02-bridge-design.md §3");
        }
    }
}
