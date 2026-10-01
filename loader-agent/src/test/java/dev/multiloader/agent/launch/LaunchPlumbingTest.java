package dev.multiloader.agent.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.multiloader.common.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动管线(bundler 解包 + version json 参数处理)的回归测试.
 * <p>这些代码此前只被端到端运行覆盖过.但端到端跑通**不会**在回归时告诉你哪里坏了
 * 它只会以"服务端起不来"的形式失败.这里把每个曾经踩过的坑钉成一条断言:
 * <ol>
 *   <li>bundler 摘要是 <b>SHA-256</b>(我曾按 SHA-1 写,结果是"文件明明是好的却校验失败")</li>
 *   <li>version json 里的 {@code -cp ${classpath}} 必须整对剔除,否则和我们的 {@code -cp} 冲突</li>
 *   <li>替换不完的占位符必须**丢弃**,绝不能把字面量 {@code ${...}} 传给 JVM</li>
 *   <li>带 classifier 的 native 库只能收当前平台的</li>
 * </ol>
*/
class LaunchPlumbingTest {

    // BundlerExtractor:清单解析

    @Test
    void parsesTabSeparatedListEntries() throws IOException {
        List<BundlerExtractor.ListEntry> entries = BundlerExtractor.parseList(
                "abc123\t26.3\t26.3/server-26.3.jar\n"
                        + "def456\tcom.example:lib:1.0\tcom/example/lib/1.0/lib-1.0.jar\n");

        assertEquals(2, entries.size());
        assertEquals("abc123", entries.get(0).digest());
        assertEquals("26.3", entries.get(0).id());
        assertEquals("26.3/server-26.3.jar", entries.get(0).relativePath());
        assertEquals("com.example:lib:1.0", entries.get(1).id());
    }

    @Test
    void skipsBlankLines() throws IOException {
        List<BundlerExtractor.ListEntry> entries =
                BundlerExtractor.parseList("\n\nabc\t1\tx.jar\n\n");
        assertEquals(1, entries.size());
    }

    @Test
    void rejectsMalformedListLine() {
        // 字段数不对必须明确报错:静默跳过会让"少解出一个库"变成运行期的 NoClassDefFoundError
        IOException e = assertThrows(IOException.class,
                () -> BundlerExtractor.parseList("only-two\tfields\n"));
        assertTrue(e.getMessage().contains("3 tab-separated"), e.getMessage());
    }

    // BundlerExtractor:识别与解包

    @Test
    void nonBundlerJarIsNotMistakenForBundler(@TempDir Path tmp) throws IOException {
        Path plain = tmp.resolve("plain.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(plain))) {
            out.putNextEntry(new JarEntry("net/minecraft/server/Main.class"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
        }
        assertFalse(BundlerExtractor.isBundler(plain));
        assertFalse(BundlerExtractor.isBundler(tmp.resolve("does-not-exist.jar")));
    }

    @Test
    void extractsBundlerAndVerifiesSha256Digests(@TempDir Path tmp) throws IOException {
        byte[] serverJar = "fake-inner-server-jar".getBytes(StandardCharsets.UTF_8);
        byte[] libJar = "fake-library".getBytes(StandardCharsets.UTF_8);

        Path bundler = writeBundler(tmp.resolve("server.jar"),
                digest(serverJar), serverJar,
                digest(libJar), libJar);

        assertTrue(BundlerExtractor.isBundler(bundler));

        Path extractDir = tmp.resolve("extract");
        BundlerExtractor.Bundle bundle = BundlerExtractor.extract(bundler, extractDir);

        assertEquals("net.minecraft.server.Main", bundle.mainClass());
        assertEquals("26.3", bundle.version());
        assertEquals(2, bundle.classpath().size());

        // 内层 server jar 必须排在类路径最前:它定义了主类
        Path first = bundle.classpath().get(0);
        assertTrue(first.toString().endsWith("server-26.3.jar"), first::toString);
        assertTrue(Files.isRegularFile(first));
        assertEquals("fake-inner-server-jar", Files.readString(first));

        // 幂等:再解一次不应报错
        BundlerExtractor.Bundle again = BundlerExtractor.extract(bundler, extractDir);
        assertEquals(2, again.classpath().size());
    }

    @Test
    void digestMismatchFailsAndRemovesTheCorruptFile(@TempDir Path tmp) throws IOException {
        byte[] serverJar = "real-content".getBytes(StandardCharsets.UTF_8);
        byte[] libJar = "lib".getBytes(StandardCharsets.UTF_8);

        // 对 server jar 故意写一个错误的摘要
        Path bundler = writeBundler(tmp.resolve("server.jar"),
                "0".repeat(64), serverJar,
                digest(libJar), libJar);

        Path extractDir = tmp.resolve("extract");
        IOException e = assertThrows(IOException.class,
                () -> BundlerExtractor.extract(bundler, extractDir));

        assertTrue(e.getMessage().contains("digest mismatch"), e.getMessage());
        assertTrue(e.getMessage().contains("SHA-256"),
                () -> "报错必须点名算法，否则会重演'以为算法是 SHA-1'的误判: " + e.getMessage());

        // 校验失败必须删掉产物,否则下次会误以为已经解包好了
        assertFalse(Files.exists(extractDir.resolve("versions/26.3/server-26.3.jar")));
    }

    @Test
    void digestOfComputesSha256NotSha1(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("f.bin");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        // "abc" 的已知摘要(SHA-256 / SHA-1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                BundlerExtractor.digestOf(file));
        assertEquals(64, BundlerExtractor.digestOf(file).length(), "SHA-256 是 64 位十六进制");
    }

    // LaunchProfileBuilder:jvm 参数处理

    @Test
    void stripsClasspathPairFromVersionJsonJvmArgs() {
        // 这是真实踩过的坑:json 里有 "-cp ${classpath}",原样收下会和我们的 -cp 冲突
        List<String> raw = List.of("-XX:StackShadowPages=32", "-cp", "${classpath}", "--add-exports",
                "java.base/jdk.internal.misc=ALL-UNNAMED");

        List<String> resolved = LaunchProfileBuilder.resolveArguments(raw, Map.of(), true);

        assertFalse(resolved.contains("-cp"), () -> "必须剔除 -cp: " + resolved);
        assertFalse(resolved.stream().anyMatch(a -> a.contains("classpath")), () -> resolved.toString());
        assertTrue(resolved.contains("-XX:StackShadowPages=32"));
        assertTrue(resolved.contains("java.base/jdk.internal.misc=ALL-UNNAMED"));
    }

    @Test
    void substitutesKnownPlaceholdersAndDropsUnresolvableOnes() {
        List<String> raw = List.of(
                "-Djava.library.path=${natives_directory}/java",
                "-Dminecraft.launcher.brand=${launcher_name}",
                "-Dfoo=${some_placeholder_we_cannot_supply}");

        List<String> resolved = LaunchProfileBuilder.resolveArguments(raw,
                Map.of("natives_directory", "/opt/natives", "launcher_name", "MultiLoader"), false);

        assertEquals(List.of("-Djava.library.path=/opt/natives/java",
                        "-Dminecraft.launcher.brand=MultiLoader"),
                resolved,
                "无法替换的参数必须被丢弃，绝不能把字面量 ${...} 传给 JVM");
    }

    @Test
    void classpathSeparatorPlaceholderUsesPlatformValue() {
        List<String> resolved = LaunchProfileBuilder.resolveArguments(
                List.of("-Dsep=${classpath_separator}"),
                Map.of("classpath_separator", java.io.File.pathSeparator), false);

        assertEquals(List.of("-Dsep=" + java.io.File.pathSeparator), resolved);
    }

    // LaunchProfileBuilder:rules 与库解析

    @Test
    void libraryWithoutRulesIsAllowed() {
        assertTrue(LaunchProfileBuilder.rulesAllow(json("{\"name\":\"a:b:1\"}")));
    }

    @Test
    void disallowedOsRuleExcludesLibrary() {
        String otherOs = "linux".equals(LaunchProfileBuilder.currentOsKey()) ? "windows" : "linux";

        assertFalse(LaunchProfileBuilder.rulesAllow(json("""
                {"name":"a:b:1","rules":[{"action":"allow","os":{"name":"%s"}}]}
                """.formatted(otherOs))));

        assertTrue(LaunchProfileBuilder.rulesAllow(json("""
                {"name":"a:b:1","rules":[{"action":"allow","os":{"name":"%s"}}]}
                """.formatted(LaunchProfileBuilder.currentOsKey()))));
    }

    @Test
    void classifierNativesAreFilteredByPlatform(@TempDir Path tmp) {
        Path libs = tmp.resolve("libs");

        // 其它平台的 native ->解析为 null(跳过)
        JsonObject windowsNative = json("""
                {"name":"org.lwjgl:lwjgl:3.4.3:natives-windows"}
                """);
        if (!"windows".equals(LaunchProfileBuilder.currentOsKey())) {
            org.junit.jupiter.api.Assertions.assertNull(
                    LaunchProfileBuilder.resolveLibrary(windowsNative, libs));
        }

        // 当前平台的 native ->得到路径
        JsonObject linuxNative = json("""
                {"name":"org.lwjgl:lwjgl:3.4.3:natives-linux"}
                """);
        if ("linux".equals(LaunchProfileBuilder.currentOsKey())) {
            Path resolved = LaunchProfileBuilder.resolveLibrary(linuxNative, libs);
            assertTrue(resolved.toString().contains("natives-linux"), resolved::toString);
        }
    }

    @Test
    void downloadsArtifactPathTakesPrecedenceOverComputedLayout(@TempDir Path tmp) {
        JsonObject library = json("""
                {"name":"a:b:1","downloads":{"artifact":{"path":"custom/layout/b-1.jar"}}}
                """);

        assertEquals(tmp.resolve("libs").resolve("custom/layout/b-1.jar"),
                LaunchProfileBuilder.resolveLibrary(library, tmp.resolve("libs")));
    }

    @Test
    void gameArgumentsSkipPlaceholdersTogetherWithTheirFlag() {
        // 回归守卫(真实踩过两次):
        //   1) 26.3 的 arguments.game 是**纯字符串**形态,而占位符过滤早先只写在
        //      "对象形态"分支里  过滤被整个绕过,客户端收到字面量 ${auth_player_name}.
        //   2) 只丢值不丢标志会留下悬空的 --username,游戏会把下一个 --version
        //      当成它的值  比原样传占位符更难诊断.
        JsonObject root = json("""
                {"arguments":{"game":[
                  "--nogui",
                  "--username", "${auth_player_name}",
                  "--assetsDir", "${assets_root}",
                  "--width", "1920"
                ]}}
                """);

        List<String> args = LaunchProfileBuilder.collectGameArguments(root, Map.of());

        assertEquals(List.of("--nogui", "--width", "1920"), args,
                () -> "占位符必须连同它的标志一起丢弃，实际: " + args);
    }
    @Test
    void ruleGatedGameArgumentsAreSkippedBeforePairing() {
        // 被规则排除的元素不参与配对:否则 --nogui 会被误判为"值被排除了"而一起丢掉
        JsonObject root = json("""
                {"arguments":{"game":[
                  "--nogui",
                  {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}
                ]}}
                """);

        assertEquals(List.of("--nogui"), LaunchProfileBuilder.collectGameArguments(root, Map.of()));
    }

    @Test
    void featureGatedGameArgumentsAreExcluded() {
        // is_demo_user / has_custom_resolution 这类条件参数我们没有能力满足.
        // 误判为"匹配"会让游戏收到 --demo 这种改变行为的参数,
        // 而误判为"不匹配"只是少一个可选参数  默认拒绝显然更安全.
        JsonObject root = json("""
                {"arguments":{"game":[
                  "--always",
                  {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
                  {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],
                   "value":"--width"}
                ]}}
                """);

        assertEquals(List.of("--always"), LaunchProfileBuilder.collectGameArguments(root, Map.of()));
    }

    @Test
    void featureGatedRulesAlsoExcludedForLibraries() {
        // 同一条规则评估被库过滤复用,所以 features 语义在两处一致
        assertFalse(LaunchProfileBuilder.rulesAllow(json("""
                {"name":"a:b:1","rules":[{"action":"allow","features":{"is_demo_user":true}}]}
                """)));
    }

    @Test
    void nonStringGameArgumentIsIgnoredNotCrashed() {
        // 数字/布尔也满足 isJsonPrimitive  必须再判 isString,
        // 否则 JSON 里的 42 会被当成参数字符串塞给游戏
        JsonObject root = json("""
                {"arguments":{"game":["--ok", 42, true]}}
                """);

        assertEquals(List.of("--ok"), LaunchProfileBuilder.collectGameArguments(root, Map.of()));
    }

    // 离线/dev 客户端模式

    @Test
    void offlinePlaceholdersFillOnlyWhatIsRequired(@TempDir Path tmp) throws IOException {
        // 回归守卫:客户端离线模式的账号与资产值.
        // 缺任何一个客户端主类都会拒绝启动,所以"填了哪些键"本身就是要钉住的契约.
        Path libraries = Files.createDirectories(tmp.resolve("libraries"));
        Path assets = Files.createDirectories(tmp.resolve("assets"));
        Files.createDirectories(assets.resolve("indexes"));
        Files.writeString(assets.resolve("indexes/34.json"), "{}");

        Map<String, String> placeholders = new LinkedHashMap<>();
        LaunchProfileBuilder.supplyOfflinePlaceholders(placeholders, tmp.resolve("game"), libraries);

        assertEquals("Dev", placeholders.get("auth_player_name"));
        assertEquals("0", placeholders.get("auth_access_token"));
        assertEquals(tmp.resolve("game").toString(), placeholders.get("game_directory"));
        assertEquals(assets.toString(), placeholders.get("assets_root"));
        assertEquals("34", placeholders.get("assets_index_name"));

        // 可选参数刻意不填:填了空串会在命令行上留下没有值的悬空标志,
        // 那比丢掉整个参数更难诊断.
        assertFalse(placeholders.containsKey("auth_xuid"), "可选参数不应被填充");
        assertFalse(placeholders.containsKey("clientid"), "可选参数不应被填充");
    }

    @Test
    void offlineUuidFollowsVanillaOfflinePlayerConvention(@TempDir Path tmp) throws IOException {
        // 为什么这条重要:原版服务端离线模式用 UUID.nameUUIDFromBytes("OfflinePlayer:" + name)
        // 推导玩家 UUID.沿用同一约定,dev 客户端在离线服务端上的身份才**一致** 
        // 否则刷物品栏,命令权限会因身份不匹配出现难以解释的行为.
        Path libraries = Files.createDirectories(tmp.resolve("libraries"));
        Map<String, String> placeholders = new LinkedHashMap<>();

        LaunchProfileBuilder.supplyOfflinePlaceholders(placeholders, tmp, libraries);

        String expected = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + LaunchProfileBuilder.OFFLINE_USERNAME).getBytes(StandardCharsets.UTF_8))
                .toString();
        assertEquals(expected, placeholders.get("auth_uuid"));
        // 必须是合法 UUID 文本,且是 type-3(名字型)版本位为 '3'
        assertEquals('3', placeholders.get("auth_uuid").charAt(14),
                () -> "应为 type-3 名字型 UUID，实际: " + placeholders.get("auth_uuid"));
    }

    @Test
    void newestAssetIndexUsesModificationTimeNotLexicographicOrder(@TempDir Path tmp) throws IOException {
        // 回归守卫:资产索引名是纯数字,字典序在位数不同时会给出**错误**答案
        // ("9" 的字典序大于 "34").必须按修改时间取最新.
        Path indexes = Files.createDirectories(tmp.resolve("assets/indexes"));
        Path older = Files.writeString(indexes.resolve("9.json"), "{}");
        Path newer = Files.writeString(indexes.resolve("34.json"), "{}");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000));

        assertEquals("34", LaunchProfileBuilder.newestAssetIndex(tmp.resolve("assets")),
                "应按修改时间取最新，而不是按名字字典序");
    }

    @Test
    void newestAssetIndexIsNullWhenAbsent(@TempDir Path tmp) throws IOException {
        // 没有 indexes 目录时要返回 null(调用方据此给出告警),而不是抛异常 
        // 资源缺失是一台机器可能出现的正常状态,不该让启动器崩掉.
        assertNull(LaunchProfileBuilder.newestAssetIndex(tmp.resolve("no-such-assets")));

        Path indexes = Files.createDirectories(tmp.resolve("assets/indexes"));
        assertNull(LaunchProfileBuilder.newestAssetIndex(tmp.resolve("assets")),
                "空目录应返回 null");
        Files.writeString(indexes.resolve("not-an-index.txt"), "x");
        assertNull(LaunchProfileBuilder.newestAssetIndex(tmp.resolve("assets")),
                "非 .json 文件不应被当作索引");
    }

    // LaunchProfile:类路径切分

    @Test
    void gameClasspathExcludesLoaderOwnEntries(@TempDir Path tmp) {
        List<Path> all = List.of(tmp.resolve("a.jar"), tmp.resolve("b.jar"),
                tmp.resolve("loader.jar"), tmp.resolve("mixin.jar"));

        LaunchProfile profile = new LaunchProfile("Main", "net.minecraft.server.Main", all,
                List.of(), List.of("--nogui"), tmp, tmp.resolve("agent.jar"), "k=v");

        List<Path> gameOnly = profile.gameClasspath(2);
        assertEquals(2, gameOnly.size());
        assertEquals(tmp.resolve("a.jar"), gameOnly.get(0));
        assertFalse(gameOnly.contains(tmp.resolve("loader.jar")),
                "游戏层类加载器绝不能看见加载器自己的 jar，否则会重复定义我们的类");

        // extraCount 超过总数 = 声称"全部都是加载器附加的",于是游戏类路径为空.
        // 这是防御性 clamp:宁可为空也不要 IndexOutOfBounds.
        // 真实调用点是 gameClasspath(extraClasspath.size()),不会走到这个分支.
        assertTrue(profile.gameClasspath(99).isEmpty());
    }

    @Test
    void commandLineIncludesJavaagentOnlyWhenAttached(@TempDir Path tmp) {
        LaunchProfile vanilla = new LaunchProfile("net.minecraft.server.Main",
                "net.minecraft.server.Main", List.of(), List.of(), List.of("--nogui"),
                tmp, null, null);
        assertFalse(vanilla.commandLine("java").stream().anyMatch(a -> a.startsWith("-javaagent:")));

        LaunchProfile agented = new LaunchProfile("dev.multiloader.launcher.Main",
                "net.minecraft.server.Main", List.of(), List.of(), List.of("--nogui"),
                tmp, tmp.resolve("agent.jar"), "k=v");
        List<String> command = agented.commandLine("java");
        assertTrue(command.contains("-javaagent:" + tmp.resolve("agent.jar") + "=k=v"),
                () -> command.toString());
        assertTrue(command.contains("dev.multiloader.launcher.Main"));
    }

    // helpers

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static String digest(byte[] content) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

/** 造一个最小 bundler:main-class + versions.list + libraries.list + 两个内嵌条目. */
    private static Path writeBundler(Path target, String serverDigest, byte[] serverJar,
                                     String libDigest, byte[] libJar) throws IOException {
        String versionsList = serverDigest + "\t26.3\t26.3/server-26.3.jar\n";
        String librariesList = libDigest + "\tcom.example:lib:1.0\tcom/example/lib/1.0/lib-1.0.jar\n";

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            put(out, "META-INF/main-class", "net.minecraft.server.Main".getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/versions.list", versionsList.getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/libraries.list", librariesList.getBytes(StandardCharsets.UTF_8));
            put(out, "META-INF/versions/26.3/server-26.3.jar", serverJar);
            put(out, "META-INF/libraries/com/example/lib/1.0/lib-1.0.jar", libJar);
        }
        return target;
    }

    private static void put(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }
}
