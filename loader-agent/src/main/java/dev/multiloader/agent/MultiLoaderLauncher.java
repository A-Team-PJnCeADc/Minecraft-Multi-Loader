package dev.multiloader.agent;

import dev.multiloader.agent.launch.GameProcessLauncher;
import dev.multiloader.agent.launch.LaunchProfile;
import dev.multiloader.agent.launch.LaunchProfileBuilder;
import dev.multiloader.common.Log;
import dev.multiloader.common.Side;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 启动器入口(Q1 决策的落地点).
 * <p>职责:根据 Mojang 的 version json 拼出启动配置,然后**另起一个 JVM**
 * 把游戏拉起来,并按需挂上 {@code -javaagent}.
 * <p>为什么是另起 JVM:{@code -javaagent} 的 premain 只在 JVM 启动时执行一次,
 * 无法对已经在跑的 JVM 生效.真实启动器也是这么做的.
 * <p>用法:
 * <pre>
 *   --bundle &lt;server.jar&gt;    Mojang server bundler(服务端路线,优先于 versions-dir)
 *   --versions-dir &lt;dir&gt;     含 &lt;version&gt;/&lt;version&gt;.json 的目录(客户端路线)
 *   --libraries-dir &lt;dir&gt;    Mojang libraries 根目录(客户端路线需要)
 *   --game-dir &lt;dir&gt;         游戏工作目录(写 eula.txt / logs / world)
 *   --mc-version &lt;ver&gt;       默认 26.3
 *   --side server|client     默认 server
 *   --extra-cp a,b,c         追加到类路径的条目
 *   --jvm-args "a b c"       追加的 JVM 参数(bundle 路线用)
 *   --agent-jar &lt;path&gt;       agent jar;配合 --vanilla 时不挂
 *   --agent-args "k=v;k=v"   agent 参数
 *   --vanilla                不挂 agent(基线对照)
 *   --smoke                  看到 "Done (" 后自动 stop
 *   --dry-run                只打印命令行,不启动
 * </pre>
*/
public final class MultiLoaderLauncher {

    private MultiLoaderLauncher() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> options = parse(args);

        Path versionsDir = options.get("versions-dir") == null ? null : Path.of(options.get("versions-dir"));
        Path librariesDir = options.get("libraries-dir") == null ? null : Path.of(options.get("libraries-dir"));
        Path bundleJar = options.get("bundle") == null ? null : Path.of(options.get("bundle"));
        Path gameDir = requiredPath(options, "game-dir");
        String mcVersion = options.getOrDefault("mc-version", "26.3");
        Side side = Side.parse(options.getOrDefault("side", "server"));

        if (versionsDir == null && bundleJar == null) {
            throw new IllegalArgumentException(
                    "需要 --bundle <server.jar> 或 --versions-dir <dir> 之一");
        }

        boolean vanilla = options.containsKey("vanilla");
        boolean smoke = options.containsKey("smoke");
        boolean dryRun = options.containsKey("dry-run");

        List<Path> extraClasspath = splitPaths(options.get("extra-cp"));
        Path agentJar = null;
        if (!vanilla) {
            agentJar = requiredPath(options, "agent-jar");
            if (!Files.isRegularFile(agentJar)) {
                throw new IOException("agent jar not found: " + agentJar
                        + " (build it first: ./gradlew :loader-agent:jar)");
            }
        }

        prepareGameDir(gameDir, side);

        LaunchProfile profile;
        if (bundleJar != null) {
            // server bundler 路线:解包 + 用 bundle 自带的主类
            List<String> serverJvmArgs = new ArrayList<>();
            String jvmArgString = options.get("jvm-args");
            if (jvmArgString != null && !jvmArgString.isBlank()) {
                serverJvmArgs.addAll(List.of(jvmArgString.split(" ")));
            }
            profile = LaunchProfileBuilder.buildFromBundle(
                    bundleJar, gameDir.resolve("bundler"), gameDir,
                    extraClasspath, agentJar, null, serverJvmArgs);
        } else {
            // 离线/dev 账号模式默认开启:客户端主类必需 --username/--uuid/--accessToken,
            // 而我们不实现认证链.--offline=false 可关掉(此时缺参数会被下面的守卫拒绝).
            boolean offlineAccount = !"false".equalsIgnoreCase(
                    options.getOrDefault("offline", "true"));
            profile = LaunchProfileBuilder.build(
                    versionsDir, mcVersion, librariesDir, gameDir, side,
                    extraClasspath, agentJar, null, offlineAccount);
        }

        // agent 参数必须在拿到 profile 之后才能拼:gameClasspath 与 gameMain 都来自 profile.
        if (agentJar != null) {
            String composed = composeAgentArgs(profile, extraClasspath, side, mcVersion,
                    gameDir, options.get("agent-args"));
            profile = new LaunchProfile(profile.mainClass(), profile.gameMainClass(),
                    profile.classpath(), profile.jvmArgs(), profile.gameArgs(),
                    profile.workingDir(), profile.agentJar(), composed);
        }

        System.out.println("========== launch profile ==========");
        System.out.println(profile.summarize());

        // 客户端侧的前置检查:我们不是完整启动器,填不了账号与资产占位符.
        // 与其产出一条注定起不来的命令行让人去猜,不如在这里明确拒绝.
        // --dry-run 仍然允许,因为查看拼装结果是排障所需.
        // 判据是"**最终参数里必需标志是否齐备**",而不是"version json 里有没有占位符".
        // 早先按后者判断,导致离线模式明明已经填好了账号与资产,守卫仍然恒报缺失
        //  因为它只看原始 json,看不见我们填了什么.
        // 按"必需标志是否出现"判断,对离线填充与外部提供两种情况都成立.
        if (side.isClient() && !dryRun) {
            List<String> missing = missingClientArguments(profile);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("""
                        无法启动客户端：命令行缺少 %d 个必需标志：%s
                        它们只能来自账号与资源索引。离线/dev 模式默认会填充这些值；
                        若已用 --offline=false 关掉，请自行提供，或改回离线模式。
                        只想查看拼装结果：加 -PdryRun=1
                        """.formatted(missing.size(), missing));
            }
        }

        if (dryRun) {
            System.out.println("-- command line --");
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            for (String part : profile.commandLine(javaExecutable)) {
                System.out.println("  " + part);
            }
            return;
        }

        // 先把上一次是怎么结束的报出来,再开始这一次.
        // 这样"上一轮为什么没有退出码"不会留成悬案.
        GameProcessLauncher.reportPreviousRun(gameDir);

        int exitCode = GameProcessLauncher.launch(profile, smoke, side);
        System.exit(exitCode);
    }

/**
     * 客户端主类**必需**的命令行标志.
     * <p>缺任何一个客户端都起不来.这份清单是"客户端能否启动"的能力判据 
     * 与这些值从哪里来(离线填充 / 外部提供 / 真实账号)无关.
*/
    private static final List<String> REQUIRED_CLIENT_FLAGS = List.of(
            "--username", "--uuid", "--accessToken",
            "--version", "--gameDir", "--assetsDir", "--assetIndex");

/** 返回 {@link #REQUIRED_CLIENT_FLAGS} 中未出现在最终命令行里的标志(保持清单顺序). */
    private static List<String> missingClientArguments(LaunchProfile profile) {
        List<String> args = profile.gameArgs();
        return REQUIRED_CLIENT_FLAGS.stream().filter(flag -> !args.contains(flag)).toList();
    }

/**
     * 拼 agent 参数.
     * <p>{@code gameClasspath} 刻意只给**游戏自身**的条目(去掉 extraClasspath):
     * 游戏层类加载器不应该看见加载器自己的 jar,否则会重复定义我们的类.
*/
    private static String composeAgentArgs(LaunchProfile profile, List<Path> extraClasspath,
                                           Side side, String mcVersion, Path gameDir,
                                           String userArgs) {
        List<Path> gameClasspath = profile.gameClasspath(extraClasspath.size());

        StringBuilder builder = new StringBuilder();
        appendArg(builder, StartupArgs.KEY_GAME_ROOT, gameDir.toString());
        appendArg(builder, StartupArgs.KEY_MODS_DIR, gameDir.resolve("mods").toString());
        appendArg(builder, StartupArgs.KEY_SIDE, side.name().toLowerCase(Locale.ROOT));
        appendArg(builder, StartupArgs.KEY_MC_VERSION, mcVersion);
        appendArg(builder, StartupArgs.KEY_GAME_MAIN, profile.gameMainClass());
        appendArg(builder, StartupArgs.KEY_GAME_CLASSPATH,
                String.join(",", gameClasspath.stream().map(Path::toString).toList()));

        if (userArgs != null && !userArgs.isBlank()) {
            builder.append(';').append(userArgs);
        }
        return builder.toString();
    }

    private static void appendArg(StringBuilder builder, String key, String value) {
        if (!builder.isEmpty()) {
            builder.append(';');
        }
        builder.append(key).append('=').append(value);
    }

/**
     * 准备游戏目录.
     * <p>服务端需要 {@code eula=true} 才会启动.这里直接写入
     * 它只是"我同意 EULA"这个事实的记录,不涉及任何规避.
*/
    private static void prepareGameDir(Path gameDir, Side side) throws IOException {
        Files.createDirectories(gameDir);
        if (side.isServer()) {
            Path eula = gameDir.resolve("eula.txt");
            if (!Files.isRegularFile(eula)) {
                Files.writeString(eula,
                        "# Generated by MultiLoader launcher\n"
                                + "# By changing the setting below to TRUE you are indicating your agreement "
                                + "to our EULA (https://aka.ms/MinecraftEULA).\n"
                                + "eula=true\n",
                        StandardCharsets.UTF_8);
                Log.info("Wrote {}", eula);
            }
        }
    }

    //
    // CLI
    //

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument (expected --key): " + arg);
            }
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq >= 0) {
                options.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static Path requiredPath(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank() || "true".equals(value)) {
            throw new IllegalArgumentException("missing required option: --" + key + " <path>");
        }
        return Path.of(value);
    }

    private static List<Path> splitPaths(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paths.add(Path.of(trimmed));
            }
        }
        return paths;
    }
}
