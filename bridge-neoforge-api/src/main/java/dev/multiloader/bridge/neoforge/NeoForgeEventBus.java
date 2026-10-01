package dev.multiloader.bridge.neoforge;

import dev.multiloader.bridge.event.ModEvent;
import dev.multiloader.common.Log;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * NeoForge {@link IEventBus} 的桥接实现(设计见 {@code docs/02-bridge-design.md}).
 * <p><b>本类只做注册,不做分发.</b> {@code post} 按设计第 2 节抛
 * {@link UnsupportedOperationException}.注册进来的监听器被记录并分类,
 * 分发要等统一事件分发机制就位.
 * <p><b>分类发生在注册时,而不是事件发生时</b>(设计第 1.1 节):
 * 监听器归统一层还是原生层,只能由它的事件类型判定;等到事件发生再判断,
 * 那时已不知道该按哪种调用约定解释它 -- 统一层的监听器是
 * {@code Consumer<ModEvent>},原生层是 {@code @SubscribeEvent} 方法,调用约定不同.
 * <p><b>为什么 {@code register} 要读字节码之外的东西(反射方法签名)</b>:
 * NeoForge 的 {@code @SubscribeEvent} 标注在**方法**上,事件类型就是该方法的
 * 唯一参数类型.这是 NeoForge 的约定,不是我们的选择.
*/
public final class NeoForgeEventBus implements IEventBus {

/**
     * 一条已注册的监听器.
     * <p><b>{@code invoker} 为什么是统一的 {@code Consumer<Event>} 而不是 {@code Method}</b>:
     * 注册有两条来源 -- {@code @SubscribeEvent} 方法(有 {@code Method})与
     * {@code addListener(..., Consumer)}(没有 {@code Method}).若记录里存 {@code Method},
     * 第二条来源无处可存;两条路各存各的则分发时要写两个分支.
     * <p>差别只在**注册时**存在,分发时应当无差别 -- 所以在注册时把差异消解进 lambda,
     * 分发就只有一条路径.这也是为什么 {@code invokeReflectively} 的错误处理写在注册侧.
*/
    public record RegisteredListener(Object owner, Class<?> eventType, String source,
                                     Layer layer, EventPriority priority, boolean receiveCancelled,
                                     Consumer<Event> invoker) {
    }

/** 监听器去向. */
    public enum Layer {
/** 事件类型实现了 {@link ModEvent} ->归统一事件层. */
        UNIFIED,
/** NeoForge 原生事件类型 ->归原生层(分发机制未就位). */
        NATIVE
    }

    private final List<RegisteredListener> registrations = new ArrayList<>();

/** 已警告过的原生事件类型,避免每个监听器都刷一条. */
    private final Map<String, Boolean> warnedNativeTypes = new LinkedHashMap<>();

/**
     * 已注册监听器的只读快照.
     * <p>存在理由是**可验证性**:第 5 步要断言"监听器注册成功",
     * 而分发尚未实现,没有别的地方能观察到这个事实.
*/
    public List<RegisteredListener> registrations() {
        return List.copyOf(registrations);
    }

    // 注册

    @Override
    public void register(Object target) {
        if (target == null) {
            throw new IllegalArgumentException("register(null)");
        }
        int found = 0;
        for (Method method : allMethods(target.getClass())) {
            SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
            if (annotation == null || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1) {
                // NeoForge 也要求恰好一个参数.静默跳过会让"监听器没生效"
                // 无从解释,所以点名报出来.
                Log.warn("Skipping @SubscribeEvent {}#{}: expected exactly 1 parameter, found {}",
                        target.getClass().getName(), method.getName(), parameters.length);
                continue;
            }
            add(target, parameters[0], target.getClass().getName() + "#" + method.getName(),
                    annotation.priority(), annotation.receiveCanceled(),
                    event -> invokeReflectively(method, target, event));
            found++;
        }
        Log.info("NeoForgeEventBus.register({}): {} listener(s)", target.getClass().getName(), found);
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, false, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType,
            Consumer<T> consumer) {
        addListener(priority, false, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType,
            Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, receiveCanceled, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled,
            Class<T> eventType, Consumer<T> consumer) {
        add(consumer, eventType, "addListener(" + eventType.getName() + ")",
                priority, receiveCanceled,
                // eventType.cast 是**受检**转型(不是 unchecked cast):事件类型不符时
                // 立刻抛 ClassCastException 并指名道姓,而不是把错误类型喂给监听器.
                event -> consumer.accept(eventType.cast(event)));
    }

    // 无法确定事件类型的 4 个变体:明确拒绝,不猜

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        throw undeterminable("addListener(Consumer<T>)");
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        throw undeterminable("addListener(EventPriority, Consumer<T>)");
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) {
        throw undeterminable("addListener(boolean, Consumer<T>)");
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled,
            Consumer<T> consumer) {
        throw undeterminable("addListener(EventPriority, boolean, Consumer<T>)");
    }

/**
     * 这 4 个变体为什么抛异常而不是"尽力而为".
     * <p>它们的事件类型只存在于泛型签名里,而 Java 的泛型在运行期**已被擦除** --
     * 拿到的 {@code Consumer} 只是一个 lambda 实例,无法从中反查出 {@code T}.
     * 猜一个类型(例如默认成某种通用事件)会让监听器**注册成功但永不触发**,
     * 那是最难诊断的失效形态:日志显示注册成功,行为却什么都没有.
     * <p>暂时抛异常,让调用方立刻知道要么用带 {@code Class<T>} 的重载,
     * 要么等分发机制就位后重新设计这一路径.
*/
    private static UnsupportedOperationException undeterminable(String signature) {
        return new UnsupportedOperationException(
                "NeoForgeEventBus: " + signature + " 无法确定事件类型 "
                        + "泛型在运行期已擦除，无法从 Consumer 反查 T。"
                        + "请改用带 Class<T> 的重载（addListener(Class<T>, Consumer<T>) 等）。"
                        + "刻意抛异常而非猜测：猜错会让监听器注册成功但永不触发。");
    }

    @Override
    public void unregister(Object object) {
        boolean removed = registrations.removeIf(listener -> owns(listener, object));
        Log.info("NeoForgeEventBus.unregister({}): {}", object, removed ? "removed" : "not registered");
    }

    // 分发(本期不实现)

    @Override
    public <T extends Event> T post(T event) {
        if (event == null) {
            throw new IllegalArgumentException("post(null)");
        }

        // 排序键(List.sort 是稳定排序,同键保持注册顺序):
        //   1) layer:统一层先,原生层后
        //      **这一条是本加载器的选择,不是 NeoForge 的语义** --
        //        NeoForge 只有一个总线,没有"两层"的概念.
        //        我们引入两层是因为统一层的事件类型属于本工程(ModEvent),
        //        而原生层是 NeoForge 的.同一事件若两层都有人听,
        //        先让"按统一语义理解它"的那一层看到它,顺序更可预期.
        //   2) priority:HIGHEST ->LOWEST(**这条是 NeoForge 的语义**,直接照搬)
        List<RegisteredListener> ordered = new ArrayList<>(registrations);
        ordered.sort(Comparator
                .comparingInt((RegisteredListener listener) ->
                        listener.layer() == Layer.UNIFIED ? 0 : 1)
                .thenComparingInt(listener -> listener.priority().ordinal()));

        int invoked = 0;
        for (RegisteredListener listener : ordered) {
            // 用 isInstance 而非 ==:监听**父**事件类型的监听器应收到**子**类型事件 --
            // 这是 NeoForge 的语义(它按事件类层次逐级派发),不是我们的发明.
            if (!listener.eventType().isInstance(event)) {
                continue;
            }

            // 取消语义,按上游实测行为:receiveCanceled=false(默认)时,
            // 事件一旦被取消,**后续未选择接收的监听器被跳过**;
            // 过滤是逐监听器,在分发时求值的(不是"取消就整体停止").
            // 与上游的**一处刻意差异**:上游写的是 ((ICancellableEvent) e).isCanceled(),
            // 对不实现该接口的事件会抛 ClassCastException -- 而 receiveCanceled 默认
            // 是 false,意味着"给不可取消的事件注册监听器"(用默认参数)就会踩到.
            // 这里改用 instanceof:可观测语义相同(不可取消的事件永不为 canceled),
            // 但对那个组合安全.差异原因写在此处,避免后人对照上游时以为改错了.
            if (!listener.receiveCancelled()
                    && event instanceof ICancellableEvent cancellable
                    && cancellable.isCanceled()) {
                continue;
            }

            listener.invoker().accept(event);
            invoked++;
        }

        Log.info("NeoForgeEventBus.post({}): 调用 {} 个监听器（共 {} 个注册）",
                event.getClass().getName(), invoked, registrations.size());
        return event;
    }

    @Override
    public <T extends Event> T post(EventPriority phase, T event) {
        // TODO(bridge): [未实现机制] 前置条件 需要先核实 phase 的确切语义
        // phase 参数在 NeoForge 的 bus 里究竟是什么语义,**我没有核实**,所以不猜.
        // 猜错会静默改变派发范围(例如"只派发到某优先级为止"),
        // 那正是本项目反复拒绝的那类失效形态:行为看起来正常,范围却不对.
        String message = "NeoForgeEventBus.post(EventPriority, T) 尚未支持："
                + "phase 参数的确切语义未核实（NeoForge bus 里它可能限制派发范围）。"
                + "为避免猜测，请改用 post(T) 它执行完整分发。"
                + "（见 docs/02-bridge-design.md §6 本期范围）";
        Log.warn("{}", message);
        throw new UnsupportedOperationException(message);
    }

    @Override
    public void start() {
        // 在我们自己的装配模型里没有对应动作:NeoForge 的 bus.start() 是
        // "开始接收事件"的开关,而本桥接目前无分发阶段可开启.
        // 记日志而非静默 -- 静默会让"为什么 start() 没效果"无从查起.
        Log.info("NeoForgeEventBus.start(): no-op（本桥接当前无分发阶段，见设计 §2）");
    }

    // 内部

    private void add(Object owner, Class<?> eventType, String source,
            EventPriority priority, boolean receiveCanceled, Consumer<Event> invoker) {
        Layer layer = ModEvent.class.isAssignableFrom(eventType) ? Layer.UNIFIED : Layer.NATIVE;
        registrations.add(new RegisteredListener(owner, eventType, source, layer,
                priority, receiveCanceled, invoker));

        Log.info("  + [{}] {} ({}, priority={})",
                layer, source, eventType.getName(), priority);

        if (layer == Layer.NATIVE && warnedNativeTypes.putIfAbsent(eventType.getName(), true) == null) {
            // 分发机制第 2 步已就位(post 会真的调用监听器),所以这里的警告**改了措辞** --
            // 原先写的是"原生事件分发尚未实现",那已不成立.
            // 现在仍然成立的是:**没有任何东西会从 NeoForge 的游戏代码里 post 原生事件**,
            // 因为本加载器没有接入 NeoForge 的事件触发点.
            // 所以该监听器只会在"有人显式 post 这个类型"时被调用.
            Log.warn("    原生事件 {} 已注册，但本加载器不会从游戏代码触发它 "
                    + "该监听器仅在显式 post 该类型时被调用。", eventType.getName());
        }
    }

/**
     * 反射调用 {@code @SubscribeEvent} 方法,并统一异常语义.
     * <p><b>监听器抛出的异常向上传播</b>(与 NeoForge 的 bus 一致,不吞).吞掉会让
     * "事件处理里出错"表现为"看起来什么都没发生",那是最难查的一类.
     * 受检异常包一层 {@link IllegalStateException} 带出原因(而不是丢失).
*/
    private static void invokeReflectively(Method method, Object owner, Event event) {
        try {
            method.invoke(owner, event);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "无法调用监听器 " + owner.getClass().getName() + "#" + method.getName()
                            + "（非 public 且不可访问）", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "监听器 " + owner.getClass().getName() + "#" + method.getName()
                            + " 抛出受检异常", cause);
        }
    }

    private static boolean owns(RegisteredListener listener, Object object) {
        return listener.owner() == object;
    }

/** 含继承方法的全部方法(父类上的 @SubscribeEvent 同样有效). */
    private static List<Method> allMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                methods.add(method);
            }
        }
        return methods;
    }
}
