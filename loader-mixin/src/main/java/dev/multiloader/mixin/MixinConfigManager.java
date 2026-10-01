package dev.multiloader.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.common.Log;
import dev.multiloader.common.Side;
import dev.multiloader.common.io.JarEntries;
import dev.multiloader.core.resolve.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mixin 配置的聚合与解析.
 * <p>为什么"解析 mixins.json 的内容"属于本层而不是格式适配器:
 * mixins.json 是 **Mixin 框架自己的格式**,Fabric / NeoForge / Forge 三家用的是
 * 同一份 schema.适配器的职责仅是"这个 mod 声明了哪些 mixin 配置文件(以及各自在哪一侧生效)",
 * 内容语义归 Mixin 层否则三个适配器要各写一遍 JSON 解析.
 * <p>目标类与成员签名不在 mixins.json 里,而在 mixin 类的注解里,
 * 因此这里用 ASM 把 mixin 类读一遍(跳过方法体,只看注解).
*/
public final class MixinConfigManager {

/** 混入成员的注入方式.用于冲突判定与诊断. */
    public enum ConflictPolicy {
        INJECT,
        MODIFY_ARG,
        MODIFY_ARGS,
        MODIFY_VARIABLE,
        MODIFY_CONSTANT,
        REDIRECT,
        OVERWRITE,
        ACCESSOR,
        INVOKER,
        UNKNOWN
    }

/**
     * 一个混入成员.
     * <p>对 {@link ConflictPolicy#OVERWRITE},{@code name}/{@code descriptor}
     * 就是**被替换的目标成员**(Mixin 的 @Overwrite 要求签名与目标一致).
     * 对注入类注解,它们是处理器方法本身的名字,不能当作目标成员使用
     * 这一点在冲突判定里必须区分对待,否则会产出大量假冲突.
*/
    public record MixinMember(String name, String descriptor, ConflictPolicy policy) {
    }

/** 一个 mixin 类及其作用目标. */
    public record MixinDeclaration(String mixinClass,
                                   List<String> targetClasses,
                                   List<MixinMember> members) {
    }

/**
     * 一份 mixins.json.
     * <p>{@code environment} 来自适配器读出的 {@link MixinConfigRef},
     * 不是文件内容但它是"能不能加载这份配置"的决定性信息,
     * 所以必须跟着配置一起传下去.
*/
    public record MixinConfig(String modId,
                              String configName,
                              Path sourceJar,
                              boolean required,
                              String packageName,
                              String compatibilityLevel,
                              Environment environment,
                              List<MixinDeclaration> declarations) {

/** 诊断用来源标识:{@code modId:configName}. */
        public String origin() {
            return modId + ":" + configName;
        }
    }

    public record CollectionResult(List<MixinConfig> configs, List<String> problems) {
        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

    private static final Map<String, ConflictPolicy> METHOD_ANNOTATIONS = Map.of(
            "Lorg/spongepowered/asm/mixin/Overwrite;", ConflictPolicy.OVERWRITE,
            "Lorg/spongepowered/asm/mixin/injection/Inject;", ConflictPolicy.INJECT,
            "Lorg/spongepowered/asm/mixin/injection/Redirect;", ConflictPolicy.REDIRECT,
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", ConflictPolicy.MODIFY_ARG,
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", ConflictPolicy.MODIFY_ARGS,
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", ConflictPolicy.MODIFY_VARIABLE,
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", ConflictPolicy.MODIFY_CONSTANT,
            "Lorg/spongepowered/asm/mixin/gen/Accessor;", ConflictPolicy.ACCESSOR,
            "Lorg/spongepowered/asm/mixin/gen/Invoker;", ConflictPolicy.INVOKER);

/** 读取 mixin 类时的 ASM 标志:只要注解,不要方法体/调试信息/栈帧. */
    private static final int ASM_FLAGS =
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;

    private MixinConfigManager() {
    }

/**
     * 聚合全部配置,不做侧别过滤.
     * <p>用于诊断("这个实例一共声明了哪些 mixin 配置").
     * 真正要加载时必须用 {@link #collect(LoadingModList, Side)},
     * 否则服务端会去加载 client-only 配置.
*/
    public static CollectionResult collect(LoadingModList modList) {
        return collect(modList, null);
    }

/**
     * 聚合配置,并按当前侧别过滤.
     * <p>过滤是**必须**的:client-only 的 mixin 配置里引用了客户端类,
     * 在服务端加载它会崩掉整个服务端,而不只是那个 mod.
     * @param side 当前侧别;传 {@code null} 表示不过滤(仅诊断用途)
*/
    public static CollectionResult collect(LoadingModList modList, Side side) {
        List<MixinConfig> configs = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (IModFile modFile : modList.modFiles()) {
            for (MixinConfigRef ref : modFile.getMixinConfigRefs()) {
                if (side != null && !ref.appliesTo(side.isClient())) {
                    skipped.add(ref + " <- " + modFile.getPrimaryModId()
                            + " (not applicable on " + side + ")");
                    continue;
                }
                MixinConfig config = parse(modFile, ref, problems);
                if (config != null) {
                    configs.add(config);
                }
            }
        }

        Log.info("Mixin configs collected: {} loaded, {} skipped by side, {} problem(s)",
                configs.size(), skipped.size(), problems.size());
        for (String skip : skipped) {
            Log.debug("  skipped: {}", skip);
        }
        for (String problem : problems) {
            Log.warn("  {}", problem);
        }
        return new CollectionResult(List.copyOf(configs), List.copyOf(problems));
    }

/**
     * 解析单个配置.
     * @return 解析失败时返回 null,并把原因追加到 {@code problems}
*/
    static MixinConfig parse(IModFile modFile, MixinConfigRef ref, List<String> problems) {
        Path sourceJar = modFile.getFilePath();
        String configName = ref.configName();
        String origin = modFile.getPrimaryModId() + ":" + configName;

        byte[] raw = JarEntries.read(sourceJar, configName);
        if (raw == null) {
            problems.add("Mixin config not found in jar: " + origin
                    + " (looked for entry '" + configName + "' in " + sourceJar.getFileName() + ")");
            return null;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            problems.add("Mixin config is not a JSON object: " + origin + " (" + e.getMessage() + ")");
            return null;
        }

        String packageName = stringOrNull(root, "package");
        if (packageName == null || packageName.isBlank()) {
            problems.add("Mixin config missing 'package': " + origin);
            return null;
        }

        boolean required = !root.has("required") || root.get("required").getAsBoolean();
        String compatibilityLevel = stringOrNull(root, "compatibilityLevel");

        List<MixinDeclaration> declarations = new ArrayList<>();
        for (String bucket : List.of("mixins", "client", "server")) {
            for (String mixinClass : stringList(root, bucket)) {
                declarations.add(parseMixinClass(sourceJar, packageName, mixinClass, origin, problems));
            }
        }

        return new MixinConfig(modFile.getPrimaryModId(), configName, sourceJar,
                required, packageName, compatibilityLevel, ref.environment(), List.copyOf(declarations));
    }

    private static MixinDeclaration parseMixinClass(Path sourceJar, String packageName,
                                                    String mixinClass, String origin,
                                                    List<String> problems) {
        String binaryName = mixinClass.contains(".")
                ? mixinClass
                : packageName + "." + mixinClass;
        byte[] bytes = JarEntries.read(sourceJar, binaryName.replace('.', '/') + ".class");
        if (bytes == null) {
            problems.add("Mixin class not found: " + binaryName + " (declared by " + origin + ")");
            return new MixinDeclaration(binaryName, List.of(), List.of());
        }

        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytes).accept(node, ASM_FLAGS);
        } catch (RuntimeException e) {
            problems.add("Mixin class is not valid bytecode: " + binaryName + " (" + e + ")");
            return new MixinDeclaration(binaryName, List.of(), List.of());
        }

        Set<String> targets = new LinkedHashSet<>();
        // @Mixin 的保留策略是 RetentionPolicy.CLASS,因此它落在
        // **RuntimeInvisibleAnnotations** 而不是 visible.
        // 只扫 visible 会让目标集合恒为空  冲突检测于是静默失效,
        // 而且不会有任何报错(我们踩过这个坑,见 mixin-standalone-integration).
        for (List<AnnotationNode> annotations
                : List.of(listOrEmpty(node.visibleAnnotations), listOrEmpty(node.invisibleAnnotations))) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN_ANNOTATION.equals(annotation.desc)) {
                    readMixinTargets(annotation, targets);
                }
            }
        }

        List<MixinMember> members = new ArrayList<>();
        for (MethodNode method : node.methods) {
            ConflictPolicy policy = policyOf(method);
            if (policy != null) {
                members.add(new MixinMember(method.name, method.desc, policy));
            }
        }

        return new MixinDeclaration(binaryName, List.copyOf(targets), List.copyOf(members));
    }

/** null 安全的注解列表. */
    private static List<AnnotationNode> listOrEmpty(List<AnnotationNode> annotations) {
        return annotations == null ? List.of() : annotations;
    }

/**
     * 从 {@code @Mixin} 注解取目标类.
     * <p>两种写法都要支持:
     * <pre>
     *   &#64;Mixin(TargetClass.class)                        // value: Type[]
     *   &#64;Mixin(targets = "net.minecraft.Foo")             // targets: String[]
     * </pre>
*/
    private static void readMixinTargets(AnnotationNode annotation, Set<String> targets) {
        if (annotation.values == null) {
            return;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            String key = String.valueOf(annotation.values.get(i));
            Object value = annotation.values.get(i + 1);
            if (!(value instanceof List<?> list)) {
                continue;
            }
            if ("value".equals(key)) {
                for (Object item : list) {
                    if (item instanceof Type type) {
                        targets.add(type.getInternalName());
                    }
                }
            } else if ("targets".equals(key)) {
                for (Object item : list) {
                    if (item instanceof String name) {
                        targets.add(name.replace('.', '/'));
                    }
                }
            }
        }
    }

    private static ConflictPolicy policyOf(MethodNode method) {
        ConflictPolicy found = policyFrom(method.visibleAnnotations);
        if (found != null) {
            return found;
        }
        return policyFrom(method.invisibleAnnotations);
    }

    private static ConflictPolicy policyFrom(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            ConflictPolicy policy = METHOD_ANNOTATIONS.get(annotation.desc);
            if (policy != null) {
                return policy;
            }
        }
        return null;
    }

    private static String stringOrNull(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static List<String> stringList(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                values.add(item.getAsString());
            }
        }
        return values;
    }
}
