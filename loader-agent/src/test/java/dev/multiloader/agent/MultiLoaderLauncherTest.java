package dev.multiloader.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动器命令行解析的测试.
 * <p>为什么值得测:这层解析的失败模式非常隐蔽."一个参数被静默吃掉"
 * 会让启动器用一个错误的路径去跑游戏,表现为"游戏行为不对但没人报错"
 * 比直接崩掉难查得多.
 * <p>另外被 agent 参数复用的是同一套键值语法({@code k=v;k=v}),
 * 所以这里的语义同时也是 agent 参数解析的契约.
*/
class MultiLoaderLauncherTest {

    @Test
    void parsesSeparateValueArguments() {
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"--game-dir", "/tmp/g", "--mc-version", "26.3"});

        assertEquals("/tmp/g", options.get("game-dir"));
        assertEquals("26.3", options.get("mc-version"));
    }

    @Test
    void parsesEqualsSyntax() {
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"--game-dir=/tmp/g", "--side=server"});

        assertEquals("/tmp/g", options.get("game-dir"));
        assertEquals("server", options.get("side"));
    }

    @Test
    void valueMayItselfContainEquals() {
        // agent 参数里就有这种形态:--agent-args gameRoot=/x;side=server
        // 只有第一个 '=' 是分隔符,后面的都属于值.
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"--agent-args=gameRoot=/x;side=server"});

        assertEquals("gameRoot=/x;side=server", options.get("agent-args"));
    }

    @Test
    void bareFlagBecomesTrue() {
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"--vanilla", "--smoke"});

        assertEquals("true", options.get("vanilla"));
        assertEquals("true", options.get("smoke"));
    }

    @Test
    void flagFollowedByAnotherFlagDoesNotSwallowIt() {
        // 关键陷阱:--vanilla 后面跟的是另一个 flag 而不是值,
        // 不能把 "--smoke" 当成 vanilla 的值吃掉.吃掉的后果是
        // vanilla 变成字符串 "--smoke",且 smoke 标志消失  两个都错,且不报错.
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"--vanilla", "--smoke", "--game-dir", "/tmp/g"});

        assertEquals("true", options.get("vanilla"));
        assertEquals("true", options.get("smoke"));
        assertEquals("/tmp/g", options.get("game-dir"));
        assertFalse(options.containsKey("--smoke"), "键名不应带前导 --");
    }

    @Test
    void repeatedKeyKeepsLastValue() {
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"--side", "client", "--side", "server"});

        assertEquals("server", options.get("side"));
    }

    @Test
    void nonFlagArgumentIsRejected() {
        // 静默忽略一个位置参数会让用户以为生效了,实际没有
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MultiLoaderLauncher.parse(new String[]{"plain-argument"}));
        assertTrue(e.getMessage().contains("plain-argument"), e.getMessage());
    }

    @Test
    void blankArgumentsAreIgnored() {
        Map<String, String> options = MultiLoaderLauncher.parse(
                new String[]{"", "  ", "--side", "server", ""});

        assertEquals(1, options.size());
        assertEquals("server", options.get("side"));
    }

    @Test
    void noArgumentsYieldsEmptyOptions() {
        assertTrue(MultiLoaderLauncher.parse(new String[0]).isEmpty());
    }
}
