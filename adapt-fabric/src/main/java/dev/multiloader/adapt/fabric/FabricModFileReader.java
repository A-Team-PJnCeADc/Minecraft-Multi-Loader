package dev.multiloader.adapt.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.multiloader.api.lifecycle.LifecyclePhase;
import dev.multiloader.api.locating.IModFile;
import dev.multiloader.api.locating.IModFileFactory;
import dev.multiloader.api.locating.IModFileReader;
import dev.multiloader.api.locating.MixinConfigRef;
import dev.multiloader.api.locating.ModFileCandidate;
import dev.multiloader.api.locating.ModFileException;
import dev.multiloader.api.metadata.DependencyKind;
import dev.multiloader.api.metadata.Environment;
import dev.multiloader.api.metadata.ModDependency;
import dev.multiloader.api.metadata.ModMetadata;
import dev.multiloader.api.metadata.Ordering;
import dev.multiloader.common.io.JarEntries;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 读取 {@code fabric.mod.json}.
 * <p>锚点文件位于 jar 根目录(目录型 mod 则在其根).识别**只看内容**,
 * 不看文件名后缀.
*/
public final class FabricModFileReader implements IModFileReader {

    public static final String ANCHOR = "fabric.mod.json";

/** Fabric 当前的 schema 版本. */
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

/** 低于其它格式读取器,让同时含多种元数据的 jar 由更"完整"的读取器先认领. */
    private static final int PRIORITY = 100;

/** 入口点命名空间 ->统一生命周期阶段. */
    private static final Map<String, LifecyclePhase> ENTRYPOINT_PHASES = Map.of(
            "preLaunch", LifecyclePhase.PREINIT,
            "main", LifecyclePhase.INIT,
            "client", LifecyclePhase.SIDED_SETUP,
            "server", LifecyclePhase.SIDED_SETUP);

/**
     * 入口点命名空间**隐含**的侧别.
     * <p>未列出的({@code main} / {@code preLaunch})沿用 mod 级 environment.
     * 与 mixin 配置的侧别问题是同一类:只信 mod 级声明会让 {@code client} 专属入口点
     * 在专用服务端被实例化,而那个类通常引用客户端专属类型  结果是服务端崩在
     * {@code NoClassDefFoundError},且堆栈指向 mod 而不是我们的解析代码.
*/
    private static final Map<String, Environment> ENTRYPOINT_ENVIRONMENTS = Map.of(
            "client", Environment.CLIENT,
            "server", Environment.SERVER);

/** 依赖区块 ->统一依赖类型. */
    private static final Map<String, DependencyKind> DEPENDENCY_KINDS = Map.of(
            "depends", DependencyKind.REQUIRED,
            "recommends", DependencyKind.OPTIONAL,
            "suggests", DependencyKind.OPTIONAL,
            "conflicts", DependencyKind.INCOMPATIBLE,
            "breaks", DependencyKind.INCOMPATIBLE);

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean canRead(ModFileCandidate candidate) {
        return readAnchor(candidate.path()).isPresent();
    }

    @Override
    public IModFile read(ModFileCandidate candidate, IModFileFactory factory) throws ModFileException {
        Path path = candidate.path();
        String json = readAnchor(path).orElseThrow(
                () -> ModFileException.at(path, "missing " + ANCHOR));

        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw ModFileException.at(path, ANCHOR + " is not a JSON object", e);
        }

        int schemaVersion = intOr(root, "schemaVersion", SUPPORTED_SCHEMA_VERSION);
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw ModFileException.at(path, "unsupported " + ANCHOR + " schemaVersion="
                    + schemaVersion + " (supported: " + SUPPORTED_SCHEMA_VERSION + ")");
        }

        Environment environment = environmentOf(stringOrNull(root, "environment"), path);

        String modId = requireString(root, "id", path);
        String version = requireString(root, "version", path);

        ModMetadata metadata = new ModMetadata(
                modId,
                version,
                stringOrNull(root, "name"),
                stringOrNull(root, "description"),
                stringList(root, "authors"),
                stringOrNull(root, "license"),
                contactMap(root),
                environment);

        List<ModDependency> dependencies = parseDependencies(root, path);
        List<MixinConfigRef> mixinRefs = parseMixinConfigs(root, path);
        List<FabricModFileExtension.Entrypoint> entrypoints = parseEntrypoints(root, environment, path);
        List<Path> nestedJars = parseNestedJars(root, path);

        FabricModFileExtension extension = new FabricModFileExtension(
                schemaVersion,
                stringOrNull(root, "accessWidener"),
                stringMap(root, "languageAdapters"),
                stringSet(root, "provides"),
                entrypoints,
                nestedJars,
                stringList(root, "contributors"),
                iconPaths(root),
                customValues(root));

        IModFileFactory.Builder builder = factory.builder(candidate, "fabric")
                .metadata(metadata)
                .dependencies(dependencies)
                .mixinConfigRefs(mixinRefs)
                .extension(extension);

        for (Path nested : nestedJars) {
            builder.nestedLibrary(nested);
        }
        return builder.build();
    }

    // 入口点

/**
     * 解析 {@code entrypoints}.
     * <p>两种形态都支持:
     * <pre>
     *   "main": ["com.example.Mod"]
     *   "main": [{ "value": "com.example.Mod", "adapter": "kotlin" }]
     * </pre>
*/
    private static List<FabricModFileExtension.Entrypoint> parseEntrypoints(
            JsonObject root, Environment defaultEnvironment, Path path) throws ModFileException {

        JsonObject entrypoints = objectOrNull(root, "entrypoints");
        if (entrypoints == null) {
            return List.of();
        }

        List<FabricModFileExtension.Entrypoint> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : entrypoints.entrySet()) {
            String namespace = entry.getKey();
            LifecyclePhase phase = ENTRYPOINT_PHASES.get(namespace);
            if (phase == null) {
                // 未知命名空间(Fabric 允许自定义):不静默丢弃,明确拒绝
                throw ModFileException.at(path, "unknown entrypoint namespace '" + namespace
                        + "' (supported: " + ENTRYPOINT_PHASES.keySet() + ")");
            }

            // 命名空间**本身**隐含侧别,不能只信 mod 级 environment.
            // 漏掉这一步会让 "client" 入口点在专用服务端被实例化  那个类通常引用
            // 客户端专属类型,结果是服务端崩在 NoClassDefFoundError.
            Environment environment = ENTRYPOINT_ENVIRONMENTS.getOrDefault(namespace, defaultEnvironment);

            for (String className : entrypointValues(entry.getValue(), path, namespace)) {
                result.add(new FabricModFileExtension.Entrypoint(
                        namespace, className, phase, environment));
            }
        }
        return result;
    }

    private static List<String> entrypointValues(JsonElement element, Path path, String namespace)
            throws ModFileException {
        if (!element.isJsonArray()) {
            throw ModFileException.at(path, "entrypoints." + namespace + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (isJsonString(item)) {
                values.add(item.getAsString());
            } else if (item.isJsonObject() && isJsonString(item.getAsJsonObject().get("value"))) {
                values.add(item.getAsJsonObject().get("value").getAsString());
            } else {
                throw ModFileException.at(path,
                        "entrypoints." + namespace + " item must be a string or an object with 'value'");
            }
        }
        return values;
    }

    // Mixin 配置

/**
     * 解析 {@code mixins}.
     * <p>两种形态都支持,且**必须**读出各自的 environment:
     * <pre>
     *   "mixins": ["mod.mixins.json"]
     *   "mixins": [{ "config": "mod.mixins.json", "environment": "client" }]
     * </pre>
     * <p>字符串形态按双侧处理(Fabric 对它的定义就是不分侧).
     * 对象形态的 {@code environment} 缺省同样是双侧.
*/
    private static List<MixinConfigRef> parseMixinConfigs(JsonObject root, Path path)
            throws ModFileException {
        JsonElement mixins = root.get("mixins");
        if (mixins == null || mixins.isJsonNull()) {
            return List.of();
        }
        if (!mixins.isJsonArray()) {
            throw ModFileException.at(path, "'mixins' must be an array");
        }

        List<MixinConfigRef> refs = new ArrayList<>();
        for (JsonElement item : mixins.getAsJsonArray()) {
            if (isJsonString(item)) {
                refs.add(new MixinConfigRef(item.getAsString()));
            } else if (item.isJsonObject() && isJsonString(item.getAsJsonObject().get("config"))) {
                JsonObject object = item.getAsJsonObject();
                String config = object.get("config").getAsString();
                String environmentValue = stringOrNull(object, "environment");
                Environment configEnvironment = environmentValue == null
                        ? Environment.BOTH
                        : environmentOf(environmentValue, path);
                refs.add(new MixinConfigRef(config, configEnvironment));
            } else {
                throw ModFileException.at(path,
                        "'mixins' item must be a string or an object with 'config'");
            }
        }
        return refs;
    }

    // 依赖

    private static List<ModDependency> parseDependencies(JsonObject root, Path path)
            throws ModFileException {
        List<ModDependency> dependencies = new ArrayList<>();
        for (Map.Entry<String, DependencyKind> section : DEPENDENCY_KINDS.entrySet()) {
            JsonObject block = objectOrNull(root, section.getKey());
            if (block == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> dependency : block.entrySet()) {
                String range = isJsonString(dependency.getValue())
                        ? dependency.getValue().getAsString()
                        : "*";
                dependencies.add(new ModDependency(dependency.getKey(), range,
                        section.getValue(), Ordering.NONE, Environment.BOTH));
            }
        }
        return dependencies;
    }

    private static Map<String, String> contactMap(JsonObject root) {
        JsonObject contact = objectOrNull(root, "contact");
        if (contact == null) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : contact.entrySet()) {
            if (isJsonString(e.getValue())) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return map;
    }

    // 锚点读取

/** 读出锚点文件内容;不存在或不可读时返回空. */
    static Optional<String> readAnchor(Path path) {
        byte[] bytes = JarEntries.read(path, ANCHOR);
        return bytes == null ? Optional.empty() : Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

/**
     * 从 jar 条目或目录中读取一个文件.
     * <p>实现委托给 {@link JarEntries}:读写 jar 条目的公式只应存在一处,
     * Mixin 层读 mixins.json 用的是同一个方法.
*/
    public static byte[] readEntry(Path path, String entryName) {
        return JarEntries.read(path, entryName);
    }

    private static List<Path> parseNestedJars(JsonObject root, Path path) throws ModFileException {
        JsonElement jars = root.get("jars");
        if (jars == null || jars.isJsonNull()) {
            return List.of();
        }
        if (!jars.isJsonArray()) {
            throw ModFileException.at(path, "'jars' must be an array");
        }
        List<Path> nested = new ArrayList<>();
        for (JsonElement item : jars.getAsJsonArray()) {
            if (!item.isJsonObject() || !isJsonString(item.getAsJsonObject().get("file"))) {
                throw ModFileException.at(path, "'jars' item must be an object with 'file'");
            }
            String file = item.getAsJsonObject().get("file").getAsString();
            // 伪路径:嵌套 jar 在 mod jar 内部,不是文件系统路径
            nested.add(JarEntries.pseudoPath(path, file));
        }
        return nested;
    }

    // JSON 取值助手

/**
     * 解析 {@code icon}  **两种形态都要处理**.
     * <pre>
     *   "icon": "icon.png"                    单一图标,所有尺寸都用它(常见写法)
     *   "icon": { "16": "a.png", "32": "b.png" }   按尺寸各一张
     * </pre>
     * <p>只处理对象形态是个陷阱:用单字符串的 mod 会**静默没有图标** 
     * 而那是更常见的一种写法.单字符串形态用 {@code ANY_SIZE} 哨兵键表示"任意尺寸".
     * <p>非数字尺寸键(理论上不该有)只跳过该条,不让整个 mod 解析失败 
     * 一个怪键不该导致 mod 加载不了.
*/
    private static Map<Integer, String> iconPaths(com.google.gson.JsonObject root) {
        com.google.gson.JsonElement icon = root.get("icon");
        if (icon == null || icon.isJsonNull()) {
            return Map.of();
        }
        if (isJsonString(icon)) {
            return Map.of(0, icon.getAsString());
        }
        if (!icon.isJsonObject()) {
            return Map.of();
        }
        java.util.Map<Integer, String> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                : icon.getAsJsonObject().entrySet()) {
            if (!isJsonString(entry.getValue())) {
                continue;
            }
            try {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
            } catch (NumberFormatException ignored) {
                // 非数字尺寸键:跳过这一条,不影响其余.
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

/** 解析 {@code custom} 段为纯 JDK 值. */
    private static Map<String, Object> customValues(com.google.gson.JsonObject root) {
        com.google.gson.JsonObject custom = objectOrNull(root, "custom");
        if (custom == null) {
            return Map.of();
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : custom.entrySet()) {
            result.put(entry.getKey(), jsonToPlain(entry.getValue()));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

/**
     * 把任意嵌套的 JSON 值转成纯 JDK 值:String / Boolean / Number / List / Map.
     * <p>为什么转成纯 JDK 值而不是把 {@code JsonElement} 直接交出去:
     * {@code IModFileMetadata} 声明在 loader-api,而 loader-api 不能依赖 gson
     * (那会把一个 JSON 库变成核心 API 的一部分).
     * <p>用 {@code Collections.unmodifiable*} 而不是 {@code List.copyOf/Map.copyOf}:
     * 后者对 null 元素**抛异常**,而 JSON 数组里出现 null 是合法的
     * ({@code [1, null, 2]}).用 copyOf 会让一个合法的 mod 文件解析崩溃.
*/
    private static Object jsonToPlain(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
                list.add(jsonToPlain(item));
            }
            return java.util.Collections.unmodifiableList(list);
        }
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                : element.getAsJsonObject().entrySet()) {
            map.put(entry.getKey(), jsonToPlain(entry.getValue()));
        }
        return java.util.Collections.unmodifiableMap(map);
    }

/**
     * 是否是 JSON **字符串**原语.
     * <p>不能用 {@code isJsonPrimitive()} 代替:JSON 数字与布尔值同样是 primitive,
     * 于是 {@code "mixins": [42]} 会被当成配置名 "42" 接受,
     * 一路带到后面才以莫名其妙的方式炸掉.类型校验必须在读取点完成.
*/
    private static boolean isJsonString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static Environment environmentOf(String value, Path path) throws ModFileException {
        if (value == null) {
            return Environment.BOTH;
        }
        return switch (value) {
            case "*" -> Environment.BOTH;
            case "client" -> Environment.CLIENT;
            case "server" -> Environment.SERVER;
            default -> throw ModFileException.at(path, "unknown environment value '" + value + "'");
        };
    }

    private static String requireString(JsonObject root, String key, Path path) throws ModFileException {
        String value = stringOrNull(root, key);
        if (value == null || value.isBlank()) {
            throw ModFileException.at(path, "missing required field '" + key + "'");
        }
        return value;
    }

    private static String stringOrNull(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return isJsonString(element) ? element.getAsString() : null;
    }

    private static int intOr(JsonObject root, String key, int fallback) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    private static JsonObject objectOrNull(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private static List<String> stringList(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (isJsonString(item)) {
                values.add(item.getAsString());
            }
        }
        return values;
    }

    private static Set<String> stringSet(JsonObject root, String key) {
        return new LinkedHashSet<>(stringList(root, key));
    }

    private static Map<String, String> stringMap(JsonObject root, String key) {
        JsonObject object = objectOrNull(root, key);
        if (object == null) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            if (isJsonString(e.getValue())) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return map;
    }
}
