package com.luxera.companion.person;

import com.luxera.companion.common.BusinessException;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 账号ID (handle) 的**形状**: 怎么生成、怎么校验、怎么归一。
 *
 * <h2>为什么账号ID 和名字是两件事</h2>
 *
 * 名字 ({@code name}) 是给人看的, 允许重复 —— 而且在这个平台里**必然**重复: 名字是 LLM
 * 从用户那句描述里生成的, 相似的描述反复收敛到同一个名字。2026-09-17 用户看到自己聊天列表
 * 里 7 个一模一样的「小满」, 就是这件事的直接后果(那 7 个是 7 个不同的、活着的 Agent)。
 * 光靠名字, 那 7 行在界面上**无法区分**, 而这不是 bug、是名字这个概念的固有性质。
 *
 * <p>账号ID 是给人用的地址: 唯一、可修改、可以报给别人。等价的现成概念是微信号。
 * 两者正交 —— 名字回答"她叫什么", 账号ID 回答"是哪一个"。
 *
 * <h2>为什么所有规则都收在这里, 而不是散在 service 里</h2>
 *
 * 这几条规则(字母表、长度、允许的字符、保留字)是**产品的定义**, 不是实现细节。
 * 它们被单测直接钉住({@code HandlesTest}), 也只有一个地方能改 —— 校验和生成若各写一份,
 * 迟早会出现"生成器造得出、校验器不认"的输入, 那种自相矛盾在线上表现为随机 400。
 *
 * <h2>归一化: 一律小写</h2>
 *
 * 大写输入不做报错, 而是**转成小写再存**。理由: 账号ID 是要被人念出来、手打出来的,
 * {@code XiaoMan} 与 {@code xiaoman} 若算两个不同账号, 用户永远分不清自己注册的是哪个,
 * 也就永远撞得上"已被占用"。存储层只存小写, 唯一性判断因此不需要 {@code lower()} 索引,
 * 也不会出现 `citext` 那种隐式行为。
 */
public final class Handles {

    /**
     * 生成用的字母表 —— 手写而非 {@code a-z0-9}。
     *
     * <p>去掉了 {@code 0 o 1 l i}: 账号ID 最常见的用法是被人念给另一个人、或照着屏幕敲一遍,
     * 而这五个字符在多数无衬线字体里两两难分({@code 0/O}、{@code 1/l/I})。少 5 个字符
     * 换来"念一遍就能敲对", 值得 —— 空间仍有 31^10 ≈ 8.2×10^14, 碰撞不是现实问题。
     */
    private static final String LETTERS = "abcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    /** 首字符之外的可用字符 */
    private static final String ALPHABET = LETTERS + DIGITS;

    /** 自动分配的长度 */
    private static final int LENGTH = 10;

    /** 用户自选的长度下限与上限 —— 太短会撞、太长没法念 */
    private static final int MIN_LENGTH = 6;
    private static final int MAX_LENGTH = 24;

    /**
     * 必须字母开头。
     *
     * <p>这条规则的好处是把账号ID 与平台里另外两种字符串**在视觉上区分开**: 纯数字可能是
     * 手机号, {@code 8-4-4-4-12} 的形状是 UUID(所有内部 id 都是它)。用户不该有机会把
     * 一个内部 id 误当成账号ID 填进表单, 也不该出现"我的账号ID是 13800138000"这种
     * 会被当成手机号的账号。
     */
    private static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9_-]*$");

    /**
     * 保留字 —— 用了会撞上平台自己的名字。
     *
     * <p>分两类, 理由不同:
     * <ul>
     *   <li>{@code admin/system/root/official/service/support/help} —— 冒充平台。一个叫
     *       {@code official} 的账号在任何界面里都像是官方的, 而它属于某个用户。</li>
     *   <li>{@code me/self/here/all/everyone} —— 撞上界面里已有的语义。{@code /me} 是
     *       "我"这个 tab 的路由, {@code @me} 之类的前缀在别处也可能再出现。</li>
     * </ul>
     *
     * <p>刻意**只做全等匹配**: {@code admin1} 是合法的。保留字要挡的是"看起来就是官方的
     * 那一个", 不是"含有某个词的"。做成包含匹配会让可用空间莫名缩小, 且理由说不清。
     */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "root", "system", "official", "service", "support", "help",
            "luxera", "me", "self", "here", "all", "everyone", "null", "undefined", "nan");

    private Handles() {
    }

    /**
     * 归一化: 去空白 + 转小写。
     *
     * <p>{@code Locale.ROOT} 是必须的 —— 土耳其语的 {@code toLowerCase()} 会把 {@code I}
     * 变成 {@code ı}(无点 i), 而那个字符过不了 {@link #SHAPE}。用默认 locale 会让同一份
     * 输入在不同机器上得到不同结果, 属于最难查的一类不一致。
     */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验并归一化用户自选的账号ID。不合法时抛 400, 消息面向用户、hint 给出可执行的下一步。
     *
     * @return 归一化后的账号ID(一定是小写)
     */
    public static String validate(String raw) {
        String h = normalize(raw);
        if (h.isEmpty()) {
            throw BusinessException.badRequest("账号ID不能为空");
        }
        if (h.length() < MIN_LENGTH || h.length() > MAX_LENGTH) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "账号ID需要 " + MIN_LENGTH + " 到 " + MAX_LENGTH + " 个字符，当前 " + h.length() + " 个",
                    "可以试试 " + suggestFrom(h));
        }
        if (!SHAPE.matcher(h).matches()) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "账号ID只能用字母、数字、下划线(_)和短横线(-)，并且要以字母开头",
                    "可以试试 " + suggestFrom(h));
        }
        if (RESERVED.contains(h)) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "「" + h + "」是保留的账号ID，不能使用",
                    "可以试试 " + suggestFrom(h));
        }
        return h;
    }

    /**
     * 把用户那句不合法的输入**尽量**擦成合法形状, 用来给一个可直接采用的建议。
     *
     * <p>比"随便回一个随机串"好: 用户想叫 {@code 小满_01}, 建议里应当还看得出这是他想要的
     * 东西。擦不出来的部分(比如全是中文)会被替换掉, 结果总是合法的 —— 这个方法的契约是
     * "返回一个必然能通过 {@link #validate} 的字符串", 由单测钉住。
     *
     * <p>中文在这里**没有**被音译(需要词典, 而且猜错了更烦人), 而是被替换成 {@code _};
     * 于是 {@code 小满} 会建议出 {@code user_a3f2}(见末尾的兜底), 而不是一个看起来像
     * 乱码的东西。
     */
    public static String suggestFrom(String raw) {
        String h = normalize(raw).replaceAll("[^a-z0-9_-]", "_").replaceAll("_+", "_");
        h = h.replaceAll("^[^a-z]+", "");
        if (h.length() > MAX_LENGTH) {
            h = h.substring(0, MAX_LENGTH);
        }
        if (h.length() < MIN_LENGTH || !SHAPE.matcher(h).matches() || RESERVED.contains(h)) {
            // 擦不出可用的形状(或者擦出来正好是保留字) —— 兜一个随机的, 但**不是**纯随机:
            // 保留可辨识的前缀
            return "user_" + randomFrom(new SecureRandom(), 6);
        }
        return h;
    }

    /**
     * 「这个已经被占用了」时给的建议 —— 在原样保留用户意图的前提下加一个短后缀。
     *
     * <p>用户想叫 {@code xiaoman}, 而它被占了。回一句"换一个再试"把活推给了用户;
     * 回 {@code xiaoman_k3f} 则是一个他多半能接受、且大概还没被占的答案。
     * 比 {@link #suggestFrom} 更贴近原意, 所以两条路径用不同的方法。
     *
     * <p>基名会被截短, 保证结果不超过 {@link #MAX_LENGTH} —— 否则用户拿到一个
     * "看起来能用、提交又被拒"的建议, 那比不给建议更糟。
     */
    public static String suggestVariant(String taken, Random random) {
        String suffix = "_" + generate(3, random);
        String base = normalize(taken);
        int room = MAX_LENGTH - suffix.length();
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        // 截短后可能以一个不合法的前缀结尾(比如以 '_' 收尾), 但形状仍然合法:
        // 首字符没动, 字符集是原串的子集
        return base + suffix;
    }

    /** 用 {@link SecureRandom} 生成一个尚未使用的形状(唯一性由调用方查库保证)。 */
    public static String generate() {
        return generate(LENGTH, new SecureRandom());
    }

    /** 默认长度, 但随机源由调用方给 —— 调用方通常已经持有随机源, 不必再造一个。 */
    public static String generate(Random random) {
        return generate(LENGTH, random);
    }

    /**
     * 可注入随机源的生成 —— 测试要能重放出同一个值。
     *
     * <p>用 {@link SecureRandom}(而不是 {@code Random})是因为账号ID 是**可枚举面**: 一串
     * 可预测的账号ID 意味着别人能猜出还有哪些账号存在。账号ID 本身不是秘密, 但"能被列表
     * 出来"和"只能被逐个猜到"是两种不同的暴露面, 前者的代价只是换个随机源。
     */
    public static String generate(int length, Random random) {
        if (length < 1) {
            throw new IllegalArgumentException("长度至少为 1");
        }
        StringBuilder sb = new StringBuilder(length);
        // 首字符只从字母里取 —— 形状规则要求字母开头, 而字母表里有数字。
        // 少了这一步, 生成器会造出 "7km2p9qx4t" 这种自己校验不过的东西
        // (HandlesTest 的 generatedHandlesAlwaysPassValidation 正是为此而写)。
        sb.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        for (int i = 1; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String randomFrom(Random random, int length) {
        return generate(length, random);
    }
}
