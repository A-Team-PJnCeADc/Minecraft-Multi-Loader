package dev.multiloader.classloader;

import dev.multiloader.api.transform.ClassContext;
import dev.multiloader.api.transform.ClassProcessor;
import dev.multiloader.api.transform.ProcessPhase;
import dev.multiloader.api.transform.ProcessPhases;
import dev.multiloader.api.transform.TransformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转换管道排序与执行的回归测试.
 * <p>S4 验收的两条:拓扑排序有单测,no-op 处理器走完管道字节码 byte-equal.
*/
class ClassProcessorSetTest {

    private static final byte[] SAMPLE = {1, 2, 3, 4, 5};

    @Test
    void ordersByPhaseThenPriority() {
        ClassProcessorSet set = ClassProcessorSet.build(List.of(
                noop("later-default", ProcessPhases.DEFAULT, 0),
                noop("mixin-priority-5", ProcessPhases.MIXIN, 5),
                noop("mixin-priority-1", ProcessPhases.MIXIN, 1),
                noop("pre-pipeline", new ProcessPhase("remap", ProcessPhases.PRE_PIPELINE_BASE), 0)));

        assertEquals(List.of("pre-pipeline", "mixin-priority-1", "mixin-priority-5", "later-default"),
                set.orderedNames());
    }

    @Test
    void registrationOrderBreaksTies() {
        ClassProcessorSet set = ClassProcessorSet.build(List.of(
                noop("first", ProcessPhases.MIXIN, 0),
                noop("second", ProcessPhases.MIXIN, 0),
                noop("third", ProcessPhases.MIXIN, 0)));

        assertEquals(List.of("first", "second", "third"), set.orderedNames());
    }

    @Test
    void prePipelinePhaseSortsBeforeMixin() {
        // 这是"兼容层 insertFirst"能力的最小验证:
        // 核心层不认识 remap 这个阶段,只按 order 排序.
        ProcessPhase remapPhase = new ProcessPhase("remap", ProcessPhases.PRE_PIPELINE_BASE + 10);
        ClassProcessorSet set = ClassProcessorSet.build(List.of(
                noop("mixin", ProcessPhases.MIXIN, 0),
                noop("legacy-remap", remapPhase, 0)));

        assertEquals(List.of("legacy-remap", "mixin"), set.orderedNames());
        assertTrue(remapPhase.order() < ProcessPhases.MIXIN.order());
    }

    @Test
    void duplicateNamesAreRejected() {
        List<ClassProcessor> duplicates = List.of(
                noop("same", ProcessPhases.MIXIN, 0),
                noop("same", ProcessPhases.DEFAULT, 0));

        assertThrows(IllegalArgumentException.class, () -> ClassProcessorSet.build(duplicates));
    }

    @Test
    void noOpProcessorKeepsBytesByteEqual() throws TransformException {
        ClassProcessorSet set = ClassProcessorSet.build(List.of(
                noop("a", ProcessPhases.MIXIN, 0),
                noop("b", ProcessPhases.ACCESS_TRANSFORMER, 0)));

        byte[] out = set.processAll(new ClassContextImpl("x/Y", SAMPLE, null, null));

        assertArrayEquals(SAMPLE, out);
    }

    @Test
    void emptyPipelineReturnsInputUnchanged() throws TransformException {
        ClassProcessorSet empty = ClassProcessorSet.empty();

        assertTrue(empty.isEmpty());
        assertArrayEquals(SAMPLE, empty.processAll(new ClassContextImpl("x/Y", SAMPLE, null, null)));
    }

    @Test
    void handlesFalseSkipsProcessor() throws TransformException {
        AtomicBoolean invoked = new AtomicBoolean(false);
        ClassProcessor skipping = new ClassProcessor() {
            @Override
            public String name() {
                return "skipper";
            }

            @Override
            public ProcessPhase phase() {
                return ProcessPhases.MIXIN;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public boolean handles(String className, byte[] input) {
                return false;
            }

            @Override
            public byte[] process(ClassContext ctx) {
                invoked.set(true);
                return ctx.input();
            }
        };

        ClassProcessorSet set = ClassProcessorSet.build(List.of(skipping));
        set.processAll(new ClassContextImpl("x/Y", SAMPLE, null, null));

        assertFalse(invoked.get(), "handles() 返回 false 的处理器不得被调用");
        assertEquals(0, set.processorsFor("x/Y", SAMPLE).size());
    }

    @Test
    void laterProcessorSeesEarlierOutput() throws TransformException {
        // 管道语义:后一个是前一个的产物,不是原始输入
        ClassProcessorSet set = ClassProcessorSet.build(List.of(
                appending("first", (byte) 9),
                appending("second", (byte) 8)));

        byte[] out = set.processAll(new ClassContextImpl("x/Y", SAMPLE, null, null));

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 9, 8}, out);
    }

    @Test
    void describeListsOrderedPipeline() {
        ClassProcessorSet set = ClassProcessorSet.build(List.of(
                noop("mixin", ProcessPhases.MIXIN, 0),
                noop("at", ProcessPhases.ACCESS_TRANSFORMER, 7)));

        String described = set.describe();

        assertTrue(described.contains("mixin"));
        assertTrue(described.indexOf("mixin") < described.indexOf("at"));
    }

    // helpers

    static ClassProcessor noop(String name, ProcessPhase phase, int priority) {
        return new ClassProcessor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ProcessPhase phase() {
                return phase;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean handles(String className, byte[] input) {
                return true;
            }

            @Override
            public byte[] process(ClassContext ctx) {
                return ctx.input();
            }
        };
    }

    private static ClassProcessor appending(String name, byte suffix) {
        return new ClassProcessor() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ProcessPhase phase() {
                return ProcessPhases.MIXIN;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public boolean handles(String className, byte[] input) {
                return true;
            }

            @Override
            public byte[] process(ClassContext ctx) {
                byte[] in = ctx.input();
                byte[] out = new byte[in.length + 1];
                System.arraycopy(in, 0, out, 0, in.length);
                out[in.length] = suffix;
                return out;
            }
        };
    }
}
