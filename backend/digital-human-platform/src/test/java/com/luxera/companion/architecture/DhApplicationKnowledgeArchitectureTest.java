package com.luxera.companion.architecture;

import com.luxera.companion.runtime.AgentApplicationFlow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAP v1 R7 的收官断言: <b>数字人的源码里不许再出现任何一个具体应用的知识</b>。
 *
 * <h2>为什么不是 ArchUnit</h2>
 * <p>同模块里另外几个边界守卫({@code ModuleBoundaryArchitectureTest} 等)用的是 ArchUnit,
 * 它擅长的是"谁依赖了谁""类名长什么样"。而这里要禁的东西恰恰两样都不是 ——
 * {@code game.make_move} 是一段字符串, {@code board} 是一个变量名, 它们可以出现在任何一个
 * 依赖关系完全干净的类里。所以这一条只能是源码文本扫描。用 ArchUnit 写会得到一个"通过"
 * 但什么也没检查的规则, 那比不写更糟。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li><b>棋类知识: 一处都不许有。</b>井字棋、五子棋、棋盘、胜负判定、{@code game.*} 动作 id —
 *       这些都是应用的事。数字人通过 {@code ApplicationRuntimePort} 拿到
 *       {@code pendingActions} 和 {@code agentHint}, 它不需要知道自己在下的叫什么棋。</li>
 *   <li><b>提醒: 只有一道门。</b>提醒应用的 id、它的动作 id、它的资源 URI 前缀, 只允许出现在
 *       {@code tool/ReminderService.java} 里 —— 那个类的注释写着"数字人侧通往
 *       com.luxera.reminder 的唯一一道门"。R5 的架构决定是"提醒归应用, 数字人只读",
 *       但数字人总得有个会说这门语言的适配器; 这一条限制的是那个适配器<b>只有一个</b>,
 *       而不是"一个都不许有"。{@code ReminderController} / {@code Reminder} 是前端契约
 *       (V10 定下的 REST 形状), 它们只认识数字人自己的 {@code Reminder} 类型, 不认识应用的
 *       字段名 —— 这正是它们不在白名单里的原因。</li>
 * </ol>
 *
 * <p>第二条还额外断言"白名单不是空壳": {@code ReminderService} 必须<b>仍然</b>写着那个应用 id。
 * 否则把提醒整个删掉也能让这个测试变绿 —— 一个靠"什么都没做"通过的守卫比没有守卫更糟。
 */
class DhApplicationKnowledgeArchitectureTest {

    /** 允许出现提醒应用身份与词汇的唯一一个文件(相对 {@code src/main/java})。 */
    private static final String REMINDER_GATEWAY = "com/luxera/companion/tool/ReminderService.java";

    /** 棋类知识 —— 没有白名单, 一处都不许有。 */
    private static final List<Pattern> GAME_KNOWLEDGE = List.of(
            contains("tictactoe"),
            contains("gomoku"),
            contains("井字棋"),
            contains("五子棋"),
            // game.play / game.make_move / game.state / game.create … 任何 game.* 的动作或能力 id
            Pattern.compile("\\bgame\\.[a-z_]+", Pattern.CASE_INSENSITIVE),
            // 棋盘与胜负判定 —— 状态是应用的 JSON, 数字人不解析它
            word("board"),
            word("checkWinner"),
            contains("parseBoard")
    );

    /** 提醒应用的身份与词汇 —— 只允许出现在 {@link #REMINDER_GATEWAY}。 */
    private static final List<Pattern> REMINDER_APPLICATION_KNOWLEDGE = List.of(
            contains("com.luxera.reminder"),
            Pattern.compile("\\breminder\\.(create|complete|cancel|update)\\b"),
            contains("reminder://")
    );

    @Test
    void theDigitalHumanKnowsNothingAboutAnyParticularGame() {
        List<String> offenders = new ArrayList<>();
        for (Source source : sources()) {
            for (Pattern pattern : GAME_KNOWLEDGE) {
                if (pattern.matcher(code(source.text)).find()) {
                    offenders.add(source.name + " 命中 " + pattern.pattern());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "数字人源码里出现了具体游戏的知识 —— 加第二个游戏就不该改这里:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void onlyTheReminderGatewaySpeaksTheReminderApplicationsLanguage() {
        List<String> offenders = new ArrayList<>();
        for (Source source : sources()) {
            if (REMINDER_GATEWAY.equals(source.name)) continue;
            for (Pattern pattern : REMINDER_APPLICATION_KNOWLEDGE) {
                if (pattern.matcher(code(source.text)).find()) {
                    offenders.add(source.name + " 命中 " + pattern.pattern());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "提醒应用的知识漏到了 " + REMINDER_GATEWAY + " 之外 —— 提醒的真相在应用那边, "
                        + "数字人只该有一个会说它的话的适配器:\n  " + String.join("\n  ", offenders));
    }

    @Test
    void theWhitelistIsNotVacuous() {
        Source gateway = sources().stream()
                .filter(s -> REMINDER_GATEWAY.equals(s.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 " + REMINDER_GATEWAY + " —— 白名单指着一个不存在的文件"));

        assertTrue(code(gateway.text).contains("com.luxera.reminder"),
                REMINDER_GATEWAY + " 不再带着提醒应用的 id 了。若提醒真的被整个删掉, 上面那条规则"
                        + "会因为'没人再提它'而变绿, 这不是通过, 是守卫失效。");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private record Source(String name, String text) {}

    /**
     * 去掉整行注释后再扫。
     *
     * <p>这里禁的是"代码里写死了某个应用的词汇", 而注释里写"这段历史为什么要改"是文档,
     * 不是知识 —— 一个类只要没有在运行时不认识某个应用就够了。所以按行丢掉
     * {@code //} / {@code /*} / {@code *} / {@code *}{@code /} 开头的行。
     *
     * <p>只处理整行注释, 不处理行尾注释: 后者要正确切开必须懂字符串字面量
     * ({@code "http://…"} 里就有两个斜杠), 而写错的切法会把真正的代码一起吃掉 ——
     * 一个会漏报的行尾注释, 好过一个会误报的扫描器。关键字的调用行都带代码, 漏不掉。
     */
    private static String code(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static Pattern contains(String literal) {
        return Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE);
    }

    private static Pattern word(String literal) {
        return Pattern.compile("\\b" + Pattern.quote(literal) + "\\b", Pattern.CASE_INSENSITIVE);
    }

    /**
     * 读模块的 {@code src/main/java} 下所有源码。
     *
     * <p>路径从<b>编译产物的位置</b>反推({@code target/classes} → 模块根), 而不是从
     * {@code user.dir} 猜 —— 后者在 {@code mvn -pl} 与 IDE 里可以完全不同, 而这个测试
     * 一旦走错目录就会以"零个文件、零个违规"的姿态变绿。
     */
    private static List<Source> sources() {
        Path moduleRoot;
        try {
            URI classes = AgentApplicationFlow.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            // Maven: target/classes → 模块根是上两级; Gradle: build/classes/java/main
            // → 模块根是上四级。向上逐级探测, 找到含 src/main/java 的那一层为止 ——
            // 反推逻辑本身保持(编译产物位置是最可靠的锚点), 只是构建工具变了。
            Path dir = Paths.get(classes);
            moduleRoot = null;
            for (int i = 0; i < 6 && dir != null; i++) {
                if (Files.isDirectory(dir.resolve("src/main/java"))) {
                    moduleRoot = dir;
                    break;
                }
                dir = dir.getParent();
            }
            assertTrue(moduleRoot != null,
                    "从编译产物位置向上找不到含 src/main/java 的模块根: " + Paths.get(classes));
        } catch (Exception e) {
            throw new IllegalStateException("定位模块根目录失败", e);
        }
        Path root = moduleRoot.resolve("src/main/java");
        assertTrue(Files.isDirectory(root), "找不到源码目录: " + root);

        List<Source> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                out.add(new Source(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读源码失败: " + root, e);
        }
        assertTrue(out.size() > 50,
                "只读到 " + out.size() + " 个源文件 —— 目录定位多半是错的, 这个测试现在什么也没检查");
        return out;
    }
}
