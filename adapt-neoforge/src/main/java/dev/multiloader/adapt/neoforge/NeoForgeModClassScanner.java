package dev.multiloader.adapt.neoforge;

import dev.multiloader.common.io.JarEntries;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 扫描 mod 内的 {@code @Mod} 类.
 * <p><b>为什么需要扫描而不是读元数据</b>:NeoForge 的 {@code neoforge.mods.toml}
 * **不声明** {@code @Mod} 类名.FancyModLoader 是遍历 jar 里的 class,
 * 用 ASM 找 {@code @Mod} 注解(见 {@code fml/loading/modscan/Scanner.java}).
 * 我们照做  这是格式的事实,不是实现选择.
 * <p><b>本类只做识别,不实例化.</b> 真正调用构造器需要为参数类型提供实现
 * ({@code IEventBus} 之类要桥接到统一事件系统),那是下一步.
 * 在这里把"识别"与"调用"分开,是为了让识别部分可以被独立验证 
 * 而调用部分一旦出错,堆栈会落在 mod 的构造器里,极难归因到扫描逻辑.
*/
public final class NeoForgeModClassScanner {

/** {@code net.neoforged.fml.common.Mod} 的字节码描述符. */
    private static final String MOD_DESCRIPTOR = "Lnet/neoforged/fml/common/Mod;";

    private static final String CONSTRUCTOR_NAME = "<init>";

    private NeoForgeModClassScanner() {
    }

/**
     * 一个 {@code @Mod} 类.
     * @param className                点号全限定名
     * @param declaredModId            注解 {@code value()};**空串**表示"用 toml 里声明的 modId"
     *                                 (NeoForge 的语义:注解留空则整份文件视为该 mod)
     * @param constructorSignatures    所有非合成构造器的参数类型(点号全限定名),
     *                                 每个元素是一个构造器的形参列表
     * @param annotationRetention      {@code "RUNTIME"} / {@code "CLASS"}  见下方说明
*/
    public record ModClass(String className,
                           String declaredModId,
                           List<List<String>> constructorSignatures,
                           String annotationRetention) {

/**
         * 用于注入的那个构造器.
         * <p>取**参数最多**的一个,而不是第一个:无参构造器是 Java 默认生成的最弱形态,
         * 而 NeoForge 的注入构造器必然带参数({@code IEventBus} / {@code ModContainer}).
         * 若类里同时有无参和注入构造器,取第一个会让我们"识别不到任何需要注入的东西",
         * 从而把 2b 变成静默不注入.
*/
        public List<String> primaryConstructor() {
            return constructorSignatures.stream()
                    .max(Comparator.comparingInt(List::size))
                    .orElse(List.of());
        }

/** 是否需要注入服务(有参数即需要). */
        public boolean requiresServices() {
            return !primaryConstructor().isEmpty();
        }
    }

/**
     * 扫描一个 mod(jar 或目录型)里的所有 {@code @Mod} 类.
     * <p>返回顺序按类名排序  {@link JarEntries#list} 明确不保证顺序,
     * 依赖枚举顺序的代码会在不同打包工具产出的 jar 上表现不同.
     * 排序后测试与诊断才可比.
*/
    public static List<ModClass> scan(Path modPath) {
        List<String> classEntries = JarEntries.list(modPath, ".class");
        List<ModClass> found = new ArrayList<>();
        for (String entry : classEntries) {
            byte[] bytes = JarEntries.read(modPath, entry);
            if (bytes == null) {
                continue;
            }
            ModClass modClass = scanClass(bytes);
            if (modClass != null) {
                found.add(modClass);
            }
        }
        found.sort(Comparator.comparing(ModClass::className));
        return found;
    }

/** 扫描单个类的字节码;不是 {@code @Mod} 类时返回 null. */
    static ModClass scanClass(byte[] classBytes) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            // 单个类读不动不该让整个 mod 的发现失败  它可能只是一个损坏的条目.
            // 但要**可见**:静默跳过会让"某个 mod 的 @Mod 类没被发现"无从解释.
            dev.multiloader.common.Log.warn(
                    "Skipping unreadable class while scanning for @Mod: {}", e.toString());
            return null;
        }

        // @Mod 的保留策略是 RUNTIME,所以它在 visible 通道.
        // 但这里**两个通道都读**:只看一个通道是一类已经踩过的坑
        // (@Mixin 是 CLASS 保留,只读 visible 的实现会完全看不见它).
        AnnotationNode annotation = find(node.visibleAnnotations);
        String retention = "RUNTIME";
        if (annotation == null) {
            annotation = find(node.invisibleAnnotations);
            retention = "CLASS";
        }
        if (annotation == null) {
            return null;
        }

        return new ModClass(
                node.name.replace('/', '.'),
                stringValue(annotation, "value"),
                constructorSignatures(node),
                retention);
    }

    private static AnnotationNode find(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (MOD_DESCRIPTOR.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

/**
     * 读注解的字符串成员.
     * <p>缺失或类型不符时返回**空串而不是 null**:NeoForge 里 {@code @Mod} 留空
     * 是有意义的("用 toml 的 modId"),把"没写"与"写了别的类型"都归到空串,
     * 由调用方按 NeoForge 语义处理.
*/
    private static String stringValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return "";
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                Object value = annotation.values.get(i + 1);
                return value instanceof String text ? text : "";
            }
        }
        return "";
    }

/** 所有非合成构造器的形参类型. */
    private static List<List<String>> constructorSignatures(ClassNode node) {
        List<List<String>> signatures = new ArrayList<>();
        if (node.methods == null) {
            return signatures;
        }
        for (MethodNode method : node.methods) {
            if (!CONSTRUCTOR_NAME.equals(method.name) || (method.access & 0x1000) != 0) {
                continue;   // 0x1000 = ACC_SYNTHETIC
            }
            List<String> parameters = new ArrayList<>();
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                parameters.add(argument.getClassName());
            }
            signatures.add(List.copyOf(parameters));
        }
        return List.copyOf(signatures);
    }
}
