package dev.multiloader.classloader;

import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.api.transform.ProcessPhase;
import dev.multiloader.api.transform.TransformException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 转换管道的排序与执行.
 * <p>排序规则(严格三级,保证确定性):
 * <ol>
 *   <li>{@link ProcessPhase#order()} 升序</li>
 *   <li>同阶段内 {@link ClassProcessor#priority()} 升序</li>
 *   <li>仍相同则按**注册顺序**(稳定排序),保证同一份输入每次产出同一份字节码</li>
 * </ol>
 * <p>确定性不是洁癖:Mixin 的冲突排查要靠"两次构建产物一致"才能定位差异.
 * <p>前置阶段(order 为负,见 {@code ProcessPhases.PRE_PIPELINE_BASE})自然排在
 * {@code ProcessPhases.MIXIN} 之前.核心层不定义任何具体前置阶段,
 * 只承诺排序规则这就是给旧版兼容层预留的 {@code insertFirst} 能力.
*/
public final class ClassProcessorSet {

    private static final ClassProcessorSet EMPTY = new ClassProcessorSet(List.of());

    private final List<ClassProcessor> ordered;

    private ClassProcessorSet(List<ClassProcessor> ordered) {
        this.ordered = List.copyOf(ordered);
    }

    public static ClassProcessorSet empty() {
        return EMPTY;
    }

/**
     * 排序并冻结一组处理器.
     * @throws IllegalArgumentException 处理器名字重复时(重复名会让诊断输出失去意义)
*/
    public static ClassProcessorSet build(List<ClassProcessor> processors) {
        if (processors.isEmpty()) {
            return EMPTY;
        }

        // 先记录注册顺序,作为最后一级 tiebreaker
        List<Indexed> indexed = new ArrayList<>(processors.size());
        for (int i = 0; i < processors.size(); i++) {
            indexed.add(new Indexed(processors.get(i), i));
        }

        indexed.sort(Comparator
                .comparingInt((Indexed ix) -> ix.processor().phase().order())
                .thenComparing(ix -> ix.processor().phase().name())
                .thenComparingInt(ix -> ix.processor().priority())
                .thenComparingInt(Indexed::registrationIndex));

        List<ClassProcessor> sorted = indexed.stream().map(Indexed::processor).collect(Collectors.toList());
        rejectDuplicateNames(sorted);
        return new ClassProcessorSet(sorted);
    }

    private static void rejectDuplicateNames(List<ClassProcessor> processors) {
        List<String> seen = new ArrayList<>(processors.size());
        for (ClassProcessor p : processors) {
            if (seen.contains(p.name())) {
                throw new IllegalArgumentException(
                        "Duplicate ClassProcessor name '" + p.name() + "'; names must be unique for diagnostics");
            }
            seen.add(p.name());
        }
    }

/** 已排序的处理器视图. */
    public List<ClassProcessor> ordered() {
        return ordered;
    }

    public List<String> orderedNames() {
        return ordered.stream().map(ClassProcessor::name).collect(Collectors.toList());
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

/**
     * 依次执行全部处理器.
     * <p>每个处理器看到的 {@code ctx.input()} 是它前一个处理器的产物.
     * 处理器返回 null 视为"不修改".
     * @return 管道最终产物;空管道时返回输入原样
*/
    public byte[] processAll(ClassContextImpl ctx) throws TransformException {
        for (ClassProcessor processor : ordered) {
            if (!processor.handles(ctx.className(), ctx.input())) {
                continue;
            }
            byte[] result = processor.process(ctx);
            ctx.update(result);
        }
        return ctx.input();
    }

/** 只问"哪些处理器会碰这个类",不执行转换.用于诊断. */
    public List<String> processorsFor(String className, byte[] input) {
        List<String> names = new ArrayList<>();
        for (ClassProcessor processor : ordered) {
            if (processor.handles(className, input)) {
                names.add(processor.name());
            }
        }
        return names;
    }

    private record Indexed(ClassProcessor processor, int registrationIndex) {
    }

/** 供诊断输出:{@code name(phase@priority)} 列表. */
    public String describe() {
        return ordered.stream()
                .map(p -> "%s(%s@%d)".formatted(p.name(), p.phase(), p.priority()))
                .collect(Collectors.joining(" -> "));
    }
}
