package dev.multiloader.agent.launch;

import dev.multiloader.common.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 拉起游戏进程,并在冒烟模式下等它"启动完成"后自动停服.
 * <p>为什么必须另起进程:{@code -javaagent} 的 {@code premain} 只在 JVM 启动时执行一次.
 * 想在当前 JVM 里挂 agent 是做不到的(那需要 attach API + agentmain,语义也不同).
 * 所以启动器起新 JVM  这也正是真实部署形态(启动器批量拼命令行).
*/
public final class GameProcessLauncher {

/** MC 服务端启动完成的标志行. */
    private static final String SERVER_READY_MARKER = "Done (";

/** 运行状态文件名(放在 gameDir 下). */
    private static final String STATUS_FILE_NAME = "last-run-status.txt";

/** 冒烟模式下的总超时.冷启动 + 世界生成在慢机器上可能比较久. */
    private static final long SMOKE_TIMEOUT_SECONDS = 240;

    private GameProcessLauncher() {
    }

/**
     * 读取并报告**上一次**运行的状态(如果存在).
     * <p>只写不读的话,那个状态文件就没人看得到,等于白写.这里让"上一次是怎么结束的"
     * 在本次运行开始时被报出来.
     * <p>关键的一句是 {@code terminated-externally} 的提醒:那种情况下上一次运行的
     * 退出码**不存在**(进程没走到正常出口),而"没有退出码"很容易被读成"运行失败".
     * 明确说出来才能阻止这个误读  这正是当初引入状态文件的原因.
*/
    public static void reportPreviousRun(Path gameDir) {
        Path file = gameDir.resolve(STATUS_FILE_NAME);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return;
        }
        String text;
        try {
            text = java.nio.file.Files.readString(file,
                    java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            Log.warn("Could not read previous run status from {}: {}", file, e.toString());
            return;
        }
        if (text.isEmpty()) {
            return;
        }

        Log.info("Previous run status ({}):", file);
        for (String line : text.split("\n")) {
            Log.info("  {}", line);
        }
        if (text.contains("status=terminated-externally")) {
            Log.warn("  上一次运行是被外部终止的，它的退出码不存在 这不代表那次运行失败。"
                    + "判据请用运行期证据（Mixin TRANSFORMED / 注入行 / 入口点分发行）。");
        }
    }

    public static int launch(LaunchProfile profile, boolean smoke, dev.multiloader.common.Side side)
            throws IOException, InterruptedException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = profile.commandLine(javaExecutable);

        Files.createDirectories(profile.workingDir());

        Log.info("Launching game process:");
        Log.info("  java        : {}", javaExecutable);
        Log.info("  mainClass   : {}", profile.mainClass());
        Log.info("  agent       : {}", profile.agentJar() == null ? "<none>" : profile.agentJar());
        Log.info("  classpath   : {} entries", profile.classpath().size());
        Log.info("  workingDir  : {}", profile.workingDir());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(profile.workingDir().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();

        // 关停路径的状态上报(写文件,见 reportShutdownWithoutExitStatus 的说明).
        // 为什么需要它:外部终止(超时 / Ctrl-C / 任务取消)杀的是 gradle,
        // 本启动器 JVM 在走到正常路径的出口报告之前就死了,于是"这次运行是怎么结束的"
        // 永远没人报出来.留一个裸的退出码给用户猜,正是我们要避免的那类信号.
        boolean[] reported = {false};

        // 子进程清理:游戏进程是我们 ProcessBuilder 拉起的**孙进程**
        // (gradle ->本启动器 JVM ->游戏 JVM).外部超时杀的是 gradle,
        // 本 JVM 随之退出,但游戏 JVM 不会自己死  实测会留下孤儿进程
        // 继续占着 GUI 窗口与显卡.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            terminate(process, "launcher JVM shutting down");
            if (!reported[0]) {
                reportShutdownWithoutExitStatus(profile.workingDir(), process, side);
            }
        }, "multiloader-game-cleanup"));

        boolean ready = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             Writer stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {

            String line;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SMOKE_TIMEOUT_SECONDS);

            while ((line = reader.readLine()) != null) {
                System.out.println("[game] " + line);

                // 就绪判据只对**服务端**成立:服务端会打印 "Done (" 表示世界加载完成,
                // 而客户端是 GUI 进程,它在窗口打开后进入事件循环,
                // 不会打印任何可判定的就绪标记.
                // 对客户端强行套用服务端判据,结果是永远 ready=false 
                // 那行 "ready=false" 会被读成"运行失败",而实际上它完全成功.
                // 这是典型的"判据不适用却照样输出",比没有输出更糟.
                boolean readinessApplies = side.isServer();
                if (smoke && readinessApplies && !ready && line.contains(SERVER_READY_MARKER)) {
                    ready = true;
                    Log.info("Detected readiness marker; sending 'stop' to terminate the smoke run");
                    stdin.write("stop\n");
                    stdin.flush();
                }

                if (System.nanoTime() > deadline) {
                    Log.warn("Smoke timeout ({}s) reached", SMOKE_TIMEOUT_SECONDS);
                    terminate(process, "smoke timeout");
                    break;
                }
            }
        }

        int exit = process.waitFor();
        reported[0] = true;
        writeStatusFile(profile.workingDir(), side, "exited", exit, side.isServer(),
                "game process exited on its own");
        if (side.isServer()) {
            Log.info("Game process exited with code {} (ready={})", exit, ready);
        } else {
            // 客户端没有就绪标记可用,如实说明而不是打一个会被误读的 ready=false.
            // 成功与否请以运行期证据判断:日志里的
            // "Mixin TRANSFORMED ..." / 注入行 / 入口点分发行.
            Log.info("Game process exited with code {} (客户端为 GUI 进程，无就绪标记。"
                    + "成功与否看运行期证据：Mixin TRANSFORMED / 注入行)", exit);
        }
        return exit;
    }

/**
     * 关停路径的状态上报:本 JVM 在游戏退出之前就被终止了.
     * <p><b>为什么写文件而不是打日志</b>:实测发现,外部超时杀掉 gradle 时,
     * 本进程的 **stdout 管道已经随之关闭**  此时无论用 JUL 还是 {@code System.out},
     * 输出都无处可去.所以"在关停路径上往 stdout 报告"在这种终止方式下
     * 结构上不可能工作(我按这个思路改过一版,实测一行都没输出).
     * <p>文件则与管道无关:进程死后文件仍在,而且下一次运行可以读它,
     * 把"上一次是怎么结束的"补报出来.GUI 类进程的输出通道天生不可靠,
     * 这是它的常规做法.
*/
    private static void reportShutdownWithoutExitStatus(Path gameDir,
            Process process, dev.multiloader.common.Side side) {
        writeStatusFile(gameDir, side, "terminated-externally",
                -1, false,
                "launcher JVM shut down before the game process exited "
                        + "(external timeout / Ctrl-C / task cancellation); "
                        + "child process " + (process == null || !process.isAlive()
                                ? "had already exited" : "was terminated by the cleanup hook"));
    }

/**
     * 把本次运行的状态写到 {@code <gameDir>/last-run-status.txt}.
     * <p>刻意做成极简的 {@code key=value} 文本:它的读者是人和脚本,
     * 而且必须在"进程正在被终止"的恶劣时机下仍然写得出来  越简单越可靠.
     * <p>写失败不能让启动器崩掉(磁盘满 / 只读目录),所以这里吞掉异常并打一行警告:
     * 状态上报是辅助信息,不该成为新的失败源.
*/
    private static void writeStatusFile(Path gameDir, dev.multiloader.common.Side side, String status,
            int exitCode, boolean readyMarkerApplicable, String detail) {
        Path file = gameDir.resolve(STATUS_FILE_NAME);
        try {
            if (file.getParent() != null) {
                java.nio.file.Files.createDirectories(file.getParent());
            }
            StringBuilder text = new StringBuilder()
                    .append("side=").append(side.name().toLowerCase(java.util.Locale.ROOT)).append('\n')
                    .append("status=").append(status).append('\n')
                    .append("exitCode=").append(exitCode < 0 ? "<none>" : exitCode).append('\n')
                    .append("readyMarkerApplicable=").append(readyMarkerApplicable).append('\n')
                    .append("endedAt=").append(java.time.Instant.now()).append('\n')
                    .append("detail=").append(detail).append('\n');
            java.nio.file.Files.writeString(file, text.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Could not write run status to {}: {}", file, e.toString());
        }
    }

/**
     * 终止游戏进程:先礼貌请求,超时后才强杀.
     * <p>直接 {@code destroyForcibly()} 会让游戏来不及做任何收尾(刷写日志,
     * 关停线程池),日志可能缺尾;先 {@code destroy()} 给它一点时间.
     * <p>幂等:进程已退出时是空操作,所以关停钩子与正常路径可以都调用它.
*/
    private static void terminate(Process process, String reason) {
        if (process == null || !process.isAlive()) {
            return;
        }
        Log.info("Terminating game process ({})", reason);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                Log.warn("Game process did not exit within 5s; forcing");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
