package dev.multiloader.bridge.neoforge;

import dev.multiloader.bridge.event.ModEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NeoForgeEventBus} 的行为测试.
 * <p>这些测试能存在,正是因为桥接暴露了 {@code registrations()}:
 * 分发未实现,没有别的地方能观察到"注册发生了什么".
 * <p>关于 {@code @SubscribeEvent}:用的是 **bus 产物里的真实注解**,
 * 不是桩.上一个测试 mod 曾用同 FQN 的桩注解,那是权宜之计 
 * 桩会掩盖真实注解的保留策略差异(本项目已经因此吃过一次亏).
*/
class NeoForgeEventBusTest {

/** 统一层事件:实现 {@link ModEvent},与 NeoForge 的 Event 无关. */
    static final class UnifiedEvent implements ModEvent {
    }

/** 原生事件:继承 NeoForge 的 Event. */
    static final class NativeEvent extends Event {
    }

    static final class UnifiedListener {
        @SubscribeEvent
        public void onUnified(UnifiedEvent event) {
        }
    }

    static final class NativeListener {
        @SubscribeEvent
        public void onNative(NativeEvent event) {
        }
    }

/** 父类上的 @SubscribeEvent 也必须被发现. */
    static class BaseListener {
        @SubscribeEvent
        public void onUnified(UnifiedEvent event) {
        }
    }

    static final class DerivedListener extends BaseListener {
        @SubscribeEvent
        public void onNative(NativeEvent event) {
        }
    }

/** 参数数量不对:应跳过并告警,而不是静默忽略. */
    static final class BadArityListener {
        @SubscribeEvent
        public void onTwo(NativeEvent event, String extra) {
        }
    }

    private static List<NeoForgeEventBus.RegisteredListener> registrationsOf(Object listener) {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        bus.register(listener);
        return bus.registrations();
    }

    // 分类:文档 §1.1 的核心职责

    @Test
    void modEventSubclassIsRoutedToUnifiedLayer() {
        List<NeoForgeEventBus.RegisteredListener> listeners = registrationsOf(new UnifiedListener());

        assertEquals(1, listeners.size());
        assertEquals(NeoForgeEventBus.Layer.UNIFIED, listeners.get(0).layer());
        assertEquals(UnifiedEvent.class, listeners.get(0).eventType());
    }

    @Test
    void plainNeoForgeEventIsRoutedToNativeLayer() {
        List<NeoForgeEventBus.RegisteredListener> listeners = registrationsOf(new NativeListener());

        assertEquals(1, listeners.size());
        assertEquals(NeoForgeEventBus.Layer.NATIVE, listeners.get(0).layer());
    }

    @Test
    void inheritedSubscribeEventMethodsAreFound() {
        // 子类 + 父类的监听器都要被发现:NeoForge 自己也扫继承层次,
        // 只看 getDeclaredMethods() 会漏掉父类上的监听器.
        List<NeoForgeEventBus.RegisteredListener> listeners = registrationsOf(new DerivedListener());

        assertEquals(2, listeners.size());
        assertEquals(1, listeners.stream()
                .filter(l -> l.layer() == NeoForgeEventBus.Layer.UNIFIED).count());
        assertEquals(1, listeners.stream()
                .filter(l -> l.layer() == NeoForgeEventBus.Layer.NATIVE).count());
    }

    @Test
    void wrongParameterCountIsSkippedNotSilentlyIgnored() {
        // 静默跳过会让"监听器没生效"无从解释.这里断言"没被注册",
        // 而告警由实现发出(日志不便断言).
        assertEquals(List.of(), registrationsOf(new BadArityListener()));
    }

    @Test
    void priorityAndReceiveCanceledSurviveRouting() {
        // 这两个参数是 NeoForge 事件语义的一部分.桥接层若在路由时丢掉它们,
        // 将来接原生分发就无从还原  属于"信息在中间层丢失"的典型.
        NeoForgeEventBus bus = new NeoForgeEventBus();
        bus.addListener(EventPriority.HIGHEST, true, NativeEvent.class, event -> {
        });

        assertEquals(1, bus.registrations().size());
        assertEquals(EventPriority.HIGHEST, bus.registrations().get(0).priority());
        assertTrue(bus.registrations().get(0).receiveCancelled());
    }

    // 明确拒绝:不做静默降级

    @Test
    void consumerOnlyVariantsAreRejectedBecauseTypeIsErased() {
        // 泛型运行期已擦除,无法从 Consumer 反查 T.
        // 猜一个类型会让监听器**注册成功但永不触发**  最难诊断的形态,
        // 所以这里选择响亮地失败.
        NeoForgeEventBus bus = new NeoForgeEventBus();

        assertThrows(UnsupportedOperationException.class,
                () -> bus.addListener((NativeEvent event) -> {
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> bus.addListener(EventPriority.NORMAL, (NativeEvent event) -> {
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> bus.addListener(true, (NativeEvent event) -> {
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> bus.addListener(EventPriority.NORMAL, true, (NativeEvent event) -> {
                }));
        assertEquals(List.of(), bus.registrations(), "被拒绝的注册不应留下痕迹");
    }

    @Test
    void postNowDispatchesWhileTheUnverifiedPhaseVariantStillRefuses() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        NativeEvent event = new NativeEvent();

        // 第 2 步后 post(T) 是**真实分发**:没有监听器时正常返回,不抛异常,
        // 且返回**同一个事件对象**(NeoForge 的 post 返回 T,供调用方链式使用).
        assertEquals(event, bus.post(event));

        // post(EventPriority, T) 仍拒绝  但理由变了:
        // 原来是"分发机制未实现",现在是"phase 参数的确切语义未核实".
        // 两者不能一起删:分发已可用,而未核实的参数语义仍是未知数.
        assertThrows(UnsupportedOperationException.class,
                () -> bus.post(EventPriority.NORMAL, new NativeEvent()));
    }

    // 注销

    @Test
    void unregisterRemovesOnlyThatOwner() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        UnifiedListener removed = new UnifiedListener();
        NativeListener kept = new NativeListener();
        bus.register(removed);
        bus.register(kept);
        assertEquals(2, bus.registrations().size());

        bus.unregister(removed);

        assertEquals(1, bus.registrations().size());
        assertEquals(kept, bus.registrations().get(0).owner());
    }

    // 分发(第 2 步).上述 helper 的方法体是空的  它们只用于"注册了几个",
    // 断言不了"被调用".所以这里另起记录型监听器.

/** 记录被调用次数的原生事件监听器. */
    static final class RecordingNativeListener {
        final List<String> calls = new java.util.ArrayList<>();

        @SubscribeEvent
        public void onNative(NativeEvent event) {
            calls.add("native");
        }
    }

    @Test
    void postInvokesMatchingListener() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        RecordingNativeListener listener = new RecordingNativeListener();
        bus.register(listener);

        bus.post(new NativeEvent());

        // 第 2 步之前这里是 0  post 只抛异常,监听器永不执行.
        assertEquals(List.of("native"), listener.calls);
    }

    @Test
    void listenersForOtherEventTypesAreNotInvoked() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        RecordingNativeListener listener = new RecordingNativeListener();
        bus.register(listener);

        bus.post(new OtherNativeEvent());   // 类型不匹配（同为 Event 子类，但不是 NativeEvent）

        assertTrue(listener.calls.isEmpty(), "类型不符不该被调用: " + listener.calls);
    }

/** 另一个原生事件类型,仅用于"类型不匹配"的断言. */
    static final class OtherNativeEvent extends Event {
    }

/** 同一个监听器里两个优先级不同的方法,用于断言执行顺序. */
    static final class PriorityOrderListener {
        final List<String> order = new java.util.ArrayList<>();

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onLowest(NativeEvent event) {
            order.add("lowest");
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onHighest(NativeEvent event) {
            order.add("highest");
        }

        @SubscribeEvent
        public void onNormal(NativeEvent event) {
            order.add("normal");
        }
    }

    @Test
    void priorityDecidesInvocationOrderHighestFirst() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        PriorityOrderListener listener = new PriorityOrderListener();
        bus.register(listener);

        bus.post(new NativeEvent());

        // HIGHEST -> NORMAL -> LOWEST(NeoForge 的语义,直接照搬).
        // 注意这与"注册顺序"无关:注册顺序是 lowest/highest/normal,
        // 执行顺序却是 highest/normal/lowest  正是这条断言要区分的.
        assertEquals(List.of("highest", "normal", "lowest"), listener.order);
    }

/** 父类型监听器应收到子类型事件(NeoForge 按类层次派发). */
    static final class BaseTypeRecordingListener {
        final List<String> calls = new java.util.ArrayList<>();

        @SubscribeEvent
        public void onAny(Event event) {
            calls.add("any:" + event.getClass().getSimpleName());
        }
    }

    @Test
    void supertypeListenerReceivesSubtypeEvent() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        BaseTypeRecordingListener listener = new BaseTypeRecordingListener();
        bus.register(listener);

        bus.post(new NativeEvent());

        assertEquals(List.of("any:NativeEvent"), listener.calls);
    }

/** 可取消事件:ICancellableEvent 的默认方法直接读写 Event 上的字段. */
    static final class CancellableNativeEvent extends Event implements ICancellableEvent {
    }

/** 先取消事件,并把"我跑过"记下来. */
    static final class CancellingListener {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void cancel(CancellableNativeEvent event) {
            event.setCanceled(true);
        }
    }

    static final class DefaultNativeCancellableSink {
        final List<String> calls = new java.util.ArrayList<>();

        @SubscribeEvent
        public void onEvent(CancellableNativeEvent event) {
            calls.add("default");
        }
    }

    static final class ReceivesCancelledSink {
        final List<String> calls = new java.util.ArrayList<>();

        @SubscribeEvent(receiveCanceled = true)
        public void onEvent(CancellableNativeEvent event) {
            calls.add("receivesCancelled");
        }
    }

    @Test
    void receiveCanceledFalseSkipsAfterCancellationButTrueDoesNot() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        DefaultNativeCancellableSink skipping = new DefaultNativeCancellableSink();
        ReceivesCancelledSink receiving = new ReceivesCancelledSink();
        bus.register(new CancellingListener());
        bus.register(skipping);
        bus.register(receiving);

        bus.post(new CancellableNativeEvent());

        // 上游语义:receiveCanceled=false(默认)的监听器在事件被取消后被**跳过**,
        // 而 receiveCanceled=true 的照常收到  不是"取消就整体停止".
        assertTrue(skipping.calls.isEmpty(), "默认监听器应被跳过: " + skipping.calls);
        assertEquals(List.of("receivesCancelled"), receiving.calls);
    }

    @Test
    void postWithNoListenersIsHarmlessAndReturnsSameEvent() {
        NeoForgeEventBus bus = new NeoForgeEventBus();
        NativeEvent event = new NativeEvent();

        assertEquals(event, bus.post(event));
    }
}
