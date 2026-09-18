package com.luxera.companion.human.mind.memory;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.4.1 —— <b>自我记忆: "我是谁、我一贯怎么做"</b>。
 *
 * <h2>它要解决的问题: persona 是起点, 不是终点</h2>
 * 一个 agent 的初始人格来自配置({@code PersonaSpec})。跑了三个月之后,
 * 如果有人问她"你是不是不太主动找人说话", 一个只会读配置的 agent 仍然只能
 * 复述初始配置 —— 哪怕这三个月里她主动找过人几百次。
 *
 * <p>本接口就是让那句话能<b>自己长出来</b>的地方。它记的不是"我做了什么",
 * 而是"关于我自己的结论": "我一向不太主动"、"我受不了别人一直催我"。
 *
 * <h2>它与情景记忆的差别 —— 一条可执行的判据</h2>
 * 情景记忆里的一条是<b>可被遗忘的</b>; 自我记忆里的一条<b>不该被遗忘</b>。
 * 一个人可以忘记上周三发生了什么, 但不会"有点不记得自己是不是个内向的人"。
 * 因此本接口上<b>没有</b> {@code forget} —— 这不是漏写了, 见下。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不生成结论</b>。"我是内向的"这句话是从多少次行为里归纳出来的,
 *       那是认知环节的工作, 而且它需要看情景记忆。这里只负责把它存住。</li>
 *   <li><b>不被遗忘</b>。这就是上面那句"没有 forget"。一条自我认知被衰减掉,
 *       会让她的性格在长期运行中<b>漂移</b> —— 而这恰恰是最不该发生的事:
 *       用户会看到"她变了一个人", 却找不到是哪一天变的。</li>
 *   <li><b>不是 persona</b>。persona 是给定的, 这里是长出来的。
 *       两者的冲突(配置说她内向, 三个月的行为说她外向)该由谁赢,
 *       是一个产品决定, 不该由这个接口偷偷替用户决定 ——
 *       所以它只是把两边都留着。</li>
 * </ul>
 */
public interface SelfMemory {

    /** 记下一条关于自己的结论。 */
    void conclude(MemoryRecord selfKnowledge);

    /** 按主题取结论 —— 主题形如 {@code "social.initiative"}。 */
    Optional<MemoryRecord> about(String topic);

    /** 全部主题。 */
    Set<String> topics();

    /** 按重要度取若干条 —— 组装 LLM context 时用它挑"我是谁"那一小段。 */
    List<MemoryRecord> mostSalient(int limit);

    /**
     * 一条自我认知的更新。
     *
     * <p>与 {@link SemanticMemory.FactRevision} 分开, 因为它们的解读不同:
     * 事实的修正是"世界变了", 自我认知的修正是"<b>我变了</b>"。
     * 后者是行为分析里最有价值的一类数据之一, 值得有自己的类型。
     */
    record SelfRevision(String topic, String previous, String current,
                        java.time.Instant recordedAt) {

        public SelfRevision {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("自我认知的更新必须给出主题");
            }
            previous = previous == null ? "" : previous;
            current = current == null ? "" : current;
            if (recordedAt == null) {
                throw new IllegalArgumentException("自我认知的更新必须带时刻 —— 不许读系统时钟");
            }
        }

        public String describe() {
            return previous.isEmpty()
                    ? "第一次认识到 " + topic + ": " + current
                    : "关于 " + topic + ", 从「" + previous + "」变成「" + current + "」";
        }
    }

    /**
     * 关于自己的判断变过几次 —— "她变了"的直接度量。
     *
     * <p><b>为什么方法名不是 {@code revisions}</b>: {@link SemanticMemory} 上已经有一个
     * 同名的 {@code revisions(String)}, 返回的是事实的修订链。两个同名同参、只有返回类型
     * 不同的方法被一个类同时继承时, Java 会直接拒绝编译 —— 而"编译不过"在这里其实是
     * 一个<b>有用的信号</b>: 它说明"事实改了"与"我改了"确实是两件事, 不该共用一个名字。
     * 名字上加 {@code self} 前缀, 代价是长一点, 收益是读代码的人不会把两者看成一回事。
     */
    List<SelfRevision> selfRevisions(String topic);
}
