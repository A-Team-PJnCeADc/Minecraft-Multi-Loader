package dev.multiloader.agent.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.multiloader.common.Log;
import dev.multiloader.common.Side;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 从 Mojang 的 version json 构建 {@link LaunchProfile}.
 * <p>这是加载器 installer 的核心职责之一("生成 launch profile"),
 * 因此它是产品代码而不是一次性脚手架.
 * <p>三个必须处理对的细节(都是踩过的):
 * <ol>
 *   <li>json 的 jvm 参数里含 {@code -cp ${classpath}}  必须整对剔除,
 *       否则会和我们自己拼的 {@code -cp} 冲突,且字面量 {@code ${classpath}} 会变成垃圾参数.</li>
 *   <li>参数里的 {@code ${natives_directory}} 等占位符必须替换;
 *       替换不了的一律丢弃(宁可少一个参数,也不能把 {@code ${...}} 传给 JVM).</li>
 *   <li>natives 目录因启动器而异:HMCL 解包在版本目录下的 {@code natives-<os>-<arch>}.</li>
 * </ol>
*/
public final class LaunchProfileBuilder {

/** MC 26.3 的 dedicated server 主类. */
    public static final String SERVER_MAIN_CLASS = "net.minecraft.server.Main";

/** Q1 决策:挂了 agent 之后 launch profile 里的主类. */
    public static final String LAUNCHER_MAIN_CLASS = "dev.multiloader.launcher.Main";

/** 我们自称的启动器名(版本 json 里的 ${launcher_name}). */
    private static final String LAUNCHER_NAME = "MultiLoader";

    private LaunchProfileBuilder() {
    }

/**
     * @param versionsDir    存放 {@code <version>/<version>.json} 的目录
     * @param mcVersion      版本号,如 {@code 26.3}
     * @param librariesDir   Mojang libraries 根目录
     * @param gameDir        游戏工作目录(会写 eula.txt / logs / world)
     * @param side           CLIENT 用 json 里的 mainClass;SERVER 强制用 {@link #SERVER_MAIN_CLASS}
     * @param extraClasspath 追加到类路径末尾的条目(我们的模块,Mixin,测试 mod)
     * @param agentJar       agent jar;null = vanilla 基线
     * @param agentArgs      agent 参数
*/
    public static LaunchProfile build(Path versionsDir,
                                      String mcVersion,
                                      Path librariesDir,
                                      Path gameDir,
                                      Side side,
                                      List<Path> extraClasspath,
                                      Path agentJar,
                                      String agentArgs,
                                      boolean offlineAccount) throws IOException {

        Path versionDir = versionsDir.resolve(mcVersion);
        Path versionJson = versionDir.resolve(mcVersion + ".json");
        if (!Files.isRegularFile(versionJson)) {
            throw new IOException("version json not found: " + versionJson);
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("version json is not valid JSON: " + versionJson, e);
        }

        String gameMain = side == Side.SERVER
                ? SERVER_MAIN_CLASS
                : root.get("mainClass").getAsString();

        Path versionJar = versionDir.resolve(mcVersion + ".jar");
        if (!Files.isRegularFile(versionJar)) {
            throw new IOException("version jar not found: " + versionJar);
        }

        List<Path> classpath = new ArrayList<>();
        classpath.add(versionJar);

        int resolved = 0;
        int skipped = 0;
        for (JsonElement element : arrayOrEmpty(root, "libraries")) {
            JsonObject library = element.getAsJsonObject();
            if (!rulesAllow(library)) {
                continue;
            }
            Path jar = resolveLibrary(library, librariesDir);
            if (jar != null && Files.isRegularFile(jar)) {
                classpath.add(jar);
                resolved++;
            } else {
                skipped++;
            }
        }
        classpath.addAll(extraClasspath);

        Map<String, String> placeholders = buildPlaceholders(
                versionsDir, mcVersion, versionDir, librariesDir, gameDir, classpath, side);

        // 我们的类路径是自己给的,所以 json 里那对 -cp ${classpath} 必须整对剔除
        List<String> jvmArgs = resolveArguments(collectJvmArguments(root), placeholders, true);

        // 离线/dev 模式的账号与资产参数(默认开).
        // 客户端主类**必需** --username / --uuid / --accessToken,而这些只能来自账号.
        // 我们不实现认证链(那是启动器的活,与加载器核心无关)
        // dev 加载器的通行做法是填一组固定的离线值,足够跑单机与验证客户端的 Mixin.
        if (offlineAccount) {
            supplyOfflinePlaceholders(placeholders, gameDir, librariesDir);
        }

        List<String> gameArgs = side == Side.SERVER
                ? List.of("--nogui")
                : collectGameArguments(root, placeholders);

        // Q1:挂了 agent 就把主类换成 launcher.Main,由它在游戏层类加载器里反射调用 gameMain
        String launchMain = agentJar == null ? gameMain : LAUNCHER_MAIN_CLASS;

        Log.info("Launch profile built: launchMain={} gameMain={} libs resolved={} skipped={} extra={} classpath={} jvmArgs={}",
                launchMain, gameMain, resolved, skipped, extraClasspath.size(), classpath.size(), jvmArgs.size());

        return new LaunchProfile(launchMain, gameMain, List.copyOf(classpath), List.copyOf(jvmArgs),
                gameArgs, gameDir, agentJar, agentArgs);
    }

/**
     * 从 Mojang **server bundler** 构建启动配置.
     * <p>服务端没有 version json 里的 jvm 参数可用(那份 json 是客户端的),
     * 所以 JVM 参数完全由调用方给.bundler 里的清单自带 SHA-1,解包时逐条校验.
     * @param bundlerJar     {@code server.jar}(bundler 形态)
     * @param extractDir     解包根目录
*/
    public static LaunchProfile buildFromBundle(Path bundlerJar,
                                                Path extractDir,
                                                Path gameDir,
                                                List<Path> extraClasspath,
                                                Path agentJar,
                                                String agentArgs,
                                                List<String> jvmArgs) throws IOException {
        BundlerExtractor.Bundle bundle = BundlerExtractor.extract(bundlerJar, extractDir);

        List<Path> classpath = new ArrayList<>(bundle.classpath());
        classpath.addAll(extraClasspath);

        String gameMain = bundle.mainClass();
        String launchMain = agentJar == null ? gameMain : LAUNCHER_MAIN_CLASS;

        Log.info("Launch profile (bundle) built: launchMain={} gameMain={} classpath={}",
                launchMain, gameMain, classpath.size());

        return new LaunchProfile(launchMain, gameMain, List.copyOf(classpath),
                List.copyOf(jvmArgs), List.of("--nogui"), gameDir, agentJar, agentArgs);
    }

    // 占位符

/**
     * 组装 version json 里用到的启动器占位符.
     * <p>刻意**不**提供 {@code assets_root} / {@code game_assets} 这类客户端资源占位符:
     * 服务端用不到它们,缺失时对应的参数会被丢弃,比塞个错误路径更安全.
*/
    static Map<String, String> buildPlaceholders(Path versionsDir, String mcVersion, Path versionDir,
                                                 Path librariesDir, Path gameDir,
                                                 List<Path> classpath, Side side) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Path nativesDir = locateNativesDir(versionsDir, mcVersion, versionDir, gameDir);

        placeholders.put("natives_directory",
                nativesDir == null ? gameDir.resolve("natives").toString() : nativesDir.toString());
        placeholders.put("library_directory", librariesDir.toString());
        placeholders.put("classpath_separator", File.pathSeparator);
        placeholders.put("classpath", joinClasspath(classpath));
        placeholders.put("version_name", mcVersion);
        placeholders.put("launcher_name", LAUNCHER_NAME);
        placeholders.put("launcher_version", "0.1.0");

        if (side == Side.CLIENT) {
            Path assetsRoot = librariesDir.getParent() == null
                    ? null
                    : librariesDir.getParent().resolve("assets");
            if (assetsRoot != null && Files.isDirectory(assetsRoot)) {
                placeholders.put("assets_root", assetsRoot.toString());
                placeholders.put("game_assets", assetsRoot.toString());
            }
        }
        return placeholders;
    }

/**
     * 替换参数里的 {@code ${...}}.
     * <p>替换不完的一律**丢弃**:把字面量 {@code ${xxx}} 传给 JVM 会得到莫名其妙的行为,
     * 少一个可选参数则通常无害.
*/
    static List<String> resolveArguments(List<String> raw, Map<String, String> placeholders,
                                         boolean stripClasspathPair) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String arg = raw.get(i);

            if (stripClasspathPair && ("-cp".equals(arg) || "-classpath".equals(arg))) {
                // 跳过 -cp 以及它配套的那个 ${classpath}
                if (i + 1 < raw.size()) {
                    i++;
                }
                continue;
            }

            String resolved = substitute(arg, placeholders);
            if (resolved != null) {
                out.add(resolved);
            } else if (Log.isVerbose()) {
                Log.debug("Dropping unresolvable jvm argument: {}", arg);
            }
        }
        return out;
    }

    private static String substitute(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result.contains("${") ? null : result;
    }

    static String joinClasspath(List<Path> classpath) {
        return String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList());
    }

    // 库解析

/**
     * 把 {@code group:artifact:version[:classifier]} 解析成本地 jar 路径.
     * <p>优先用 json 自带的 {@code downloads.artifact.path}(权威),
     * 缺失时按 maven 布局自行计算.
*/
    static Path resolveLibrary(JsonObject library, Path librariesDir) {
        String name = library.get("name").getAsString();
        String[] parts = name.split(":");
        if (parts.length < 3) {
            return null;
        }
        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : null;

        // 带 classifier 的条目是 natives,只要当前平台的
        if (classifier != null && !classifier.toLowerCase(Locale.ROOT).contains(currentOsKey())) {
            return null;
        }

        JsonObject downloads = library.getAsJsonObject("downloads");
        if (downloads != null && downloads.has("artifact")) {
            JsonObject artifactEntry = downloads.getAsJsonObject("artifact");
            if (artifactEntry != null && artifactEntry.has("path")) {
                return librariesDir.resolve(artifactEntry.get("path").getAsString());
            }
        }

        String fileName = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        return librariesDir.resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(fileName);
    }

/** 当前平台在 Mojang rules 里的 os.name 取值. */
    static String currentOsKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

/**
     * 评估 Mojang 的 rules 数组.
     * <p>语义:不带 rules = 允许;带 rules 时逐条匹配,最后命中的那条决定结果.
*/
    static boolean rulesAllow(JsonObject library) {
        JsonArray rules = library.getAsJsonArray("rules");
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            if (!ruleMatches(rule)) {
                continue;
            }
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule) {
        // 带 features 的规则是 is_demo_user / has_custom_resolution 之类的条件参数.
        // 我们没有这些能力  判为"不匹配"比判为"匹配"安全:
        // 误判为匹配会让游戏收到 --demo 这类改变行为的参数.
        if (rule.has("features")) {
            return false;
        }

        JsonObject os = rule.getAsJsonObject("os");
        if (os == null) {
            return true;
        }
        if (os.has("name") && !currentOsKey().equals(os.get("name").getAsString())) {
            return false;
        }
        if (os.has("arch")) {
            String arch = os.get("arch").getAsString();
            String actual = System.getProperty("os.arch", "");
            boolean match = switch (arch) {
                case "x86" -> actual.equals("x86") || actual.equals("i386");
                case "x86_64", "amd64" -> actual.equals("amd64") || actual.equals("x86_64");
                case "arm64", "aarch64" -> actual.equals("aarch64") || actual.equals("arm64");
                default -> false;
            };
            if (!match) {
                return false;
            }
        }
        return true;
    }

/**
     * 找已解包好的 natives 目录.
     * <p>优先版本目录下的 {@code natives-<os>-<arch>}(HMCL / 官方启动器的做法),
     * 再退回 {@code <gameDir>/natives}.
*/
    static Path locateNativesDir(Path versionsDir, String mcVersion, Path versionDir, Path gameDir) {
        if (Files.isDirectory(versionDir)) {
            try (Stream<Path> stream = Files.list(versionDir)) {
                Optional<Path> hit = stream
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("natives-"))
                        .filter(p -> p.getFileName().toString().contains(currentOsKey()))
                        .findFirst();
                if (hit.isPresent()) {
                    return hit.get();
                }
            } catch (IOException ignored) {
                // 退回下面的候选
            }
        }
        Path fallback = gameDir.resolve("natives");
        return Files.isDirectory(fallback) ? fallback : null;
    }

    // 参数收集

/** 只收无条件的字符串 jvm 参数;带 rules 的一律跳过(平台条件参数). */
    static List<String> collectJvmArguments(JsonObject root) {
        List<String> args = new ArrayList<>();
        JsonObject arguments = root.getAsJsonObject("arguments");
        if (arguments == null) {
            return args;
        }
        for (JsonElement element : arrayOrEmpty(arguments, "jvm")) {
            if (element.isJsonPrimitive()) {
                args.add(element.getAsString());
            }
        }
        return args;
    }

/**
     * 收集游戏参数.
     * <p>version json 的 {@code arguments.game} 有两种形态,必须都处理:
     * <pre>
     *   "--nogui"                                    ← 纯字符串
     *   { "rules": [...], "value": "--demo" }        ← 带规则的字符串
     * </pre>
     * <p>三条硬规则(都是踩过的):
     * <ol>
     *   <li><b>含 {@code ${...}} 的值一律丢弃</b>.这些是启动器占位符
     *       ({@code ${auth_player_name}} / {@code ${assets_root}} ...),
     *       我们不是完整启动器,没有账号与资源索引可填.
     *       注意早先只在"对象形态"分支里做了这个过滤,而 26.3 的参数是**纯字符串**形态,
     *       于是过滤被整个绕过.</li>
     *   <li><b>占位符的"标志"要一起丢</b>.格式是 {@code --flag value} 的平铺列表,
     *       只丢值会留下悬空的 {@code --username},游戏会把下一个 {@code --version}
     *       当成它的值  比原样传占位符更难诊断.</li>
     *   <li><b>带 {@code features} 的规则一律视为不匹配</b>.那些是
     *       {@code is_demo_user} / {@code has_custom_resolution} 之类的条件参数,
     *       我们没有这些能力,默认拒绝比默认接受安全.</li>
     * </ol>
*/
    static List<String> collectGameArguments(JsonObject root, Map<String, String> placeholders) {
        List<JsonElement> raw = gameArgumentElements(root);
        List<String> args = new ArrayList<>();

        for (int i = 0; i < raw.size(); i++) {
            String literal = literalOrNull(raw.get(i));
            if (literal == null) {
                continue;   // 被规则排除 / 非字符串
            }
            // 先代入再判断:能填上的占位符(离线模式的账号/资产)应当被填上,
            // 而不是一律丢掉.只有**填不了**的才进入下面的丢弃逻辑.
            String text = substitute(literal, placeholders);
            if (text == null) {
                continue;   // 没有所属标志的孤立占位符，且我们填不了
            }

            if (text.startsWith("--")) {
                // 前瞻一格:若这个标志的值是**我们填不了**的占位符,则连标志一起丢.
                // 只丢值会留下一个没有值的悬空标志,游戏会把下一个标志当成它的值 
                // 那比丢掉整个参数更难诊断.
                if (i + 1 < raw.size() && isUnresolvablePlaceholder(raw.get(i + 1), placeholders)) {
                    i++;            // 跳过占位符本体
                    continue;       // 标志本身也不加
                }
                args.add(text);
                continue;
            }

            args.add(text);
        }
        return args;
    }

/**
     * 该元素是否为"含占位符的字符串,且我们填不了".
     * <p>只有这种情况才需要连标志一起丢.被规则排除的元素,非字符串元素
     * 不走这条  它们本来就该单独丢掉.
*/
    private static boolean isUnresolvablePlaceholder(JsonElement element, Map<String, String> placeholders) {
        String literal = literalOrNull(element);
        return literal != null && literal.contains("${") && substitute(literal, placeholders) == null;
    }

/** 离线模式使用的玩家名. */
    public static final String OFFLINE_USERNAME = "Dev";

/**
     * 填上离线模式的账号与资产占位符.
     * <p>为什么用 {@code OfflinePlayer:} 前缀生成 UUID:原版服务端的离线模式正是用
     * {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)} 推导玩家 UUID.
     * 沿用同一套约定,dev 客户端拿到的 UUID 就与离线服务端认定的**一致** 
     * 否则刷物品栏,命令权限之类会因为身份不匹配出现难以解释的行为.
     * <p>只为**必需**参数填值.可选参数(xuid / clientid / versionType 等)刻意不填,
     * 让它们连同其标志一起被丢弃  填入空串会留下一个没有值的悬空标志,
     * 那比丢掉整个参数更糟.
*/
    static void supplyOfflinePlaceholders(Map<String, String> placeholders, Path gameDir, Path librariesDir) {
        placeholders.put("auth_player_name", OFFLINE_USERNAME);
        placeholders.put("auth_uuid", UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + OFFLINE_USERNAME).getBytes(StandardCharsets.UTF_8)).toString());
        placeholders.put("auth_access_token", "0");
        placeholders.put("game_directory", gameDir.toString());

        // assets 与 libraries 是兄弟目录(<mcHome>/assets,<mcHome>/libraries).
        // 从 libraries 反推而不是写死路径:调用方给的 mcHome 就是唯一事实来源.
        Path assetsRoot = librariesDir.resolveSibling("assets");
        if (Files.isDirectory(assetsRoot)) {
            placeholders.put("assets_root", assetsRoot.toString());
            placeholders.put("game_assets", assetsRoot.toString());
            String index = newestAssetIndex(assetsRoot);
            if (index != null) {
                placeholders.put("assets_index_name", index);
            } else {
                Log.warn("offline mode: no asset index found under {} — the client may fail to start",
                        assetsRoot.resolve("indexes"));
            }
        } else {
            Log.warn("offline mode: assets directory not found at {} — the client will lack assets",
                    assetsRoot);
        }
    }

/**
     * 取 assets/indexes 下最新的资产索引名.
     * <p>按**修改时间**而非字典序取最新:索引名是纯数字(如 {@code 34}),
     * 字典序在位数不同时会给出错误答案("9" > "34").
     * @return 不含 .json 后缀的索引名;找不到返回 {@code null}
*/
    static String newestAssetIndex(Path assetsRoot) {
        Path indexes = assetsRoot.resolve("indexes");
        if (!Files.isDirectory(indexes)) {
            return null;
        }
        try (var stream = Files.list(indexes)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(LaunchProfileBuilder::lastModified))
                    .map(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.substring(0, fileName.length() - ".json".length());
                    })
                    .orElse(null);
        } catch (IOException e) {
            Log.warn("offline mode: could not list {}: {}", indexes, e.toString());
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

/** {@code arguments.game} 的原始元素;缺失时为空列表. */
    private static List<JsonElement> gameArgumentElements(JsonObject root) {
        JsonObject arguments = root.getAsJsonObject("arguments");
        if (arguments == null) {
            return List.of();
        }
        JsonArray array = arguments.getAsJsonArray("game");
        if (array == null) {
            return List.of();
        }
        List<JsonElement> elements = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("rules") && !rulesAllow(entry)) {
                    continue;   // 规则不满足（含 features 的都会被判为不满足）
                }
                elements.add(entry.get("value") == null ? JsonNull.INSTANCE : entry.get("value"));
            } else {
                elements.add(element);
            }
        }
        return elements;
    }

/**
     * 元素是否是"我们填不了的启动器占位符".
     * <p>用 {@code contains} 而不是 {@code startsWith}:真实 json 里有
     * {@code "-Dfoo=${bar}"} 这种把占位符嵌在中间的形式.
*/
    private static boolean isPlaceholder(JsonElement element) {
        String text = literalOrNull(element);
        return text != null && text.contains("${");
    }

/** 取字符串字面量;非字符串(数字/布尔/null)或被排除时返回 null. */
    private static String literalOrNull(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static JsonArray arrayOrEmpty(JsonObject root, String key) {
        JsonArray array = root.getAsJsonArray(key);
        return array == null ? new JsonArray() : array;
    }
}
