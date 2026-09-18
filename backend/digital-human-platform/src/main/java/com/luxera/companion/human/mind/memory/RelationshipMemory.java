package com.luxera.companion.human.mind.memory;

import com.luxera.companion.human.mind.relationship.PersonId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V2.2 §3.4.1 —— <b>关系记忆: "我对她的看法与这段关系的变迁"</b>。
 *
 * <h2>它与 {@code RelationshipGraph} 的分工 —— 两句话就能说清</h2>
 * <table border="1">
 *   <tr><th></th><th>答什么</th><th>形状</th></tr>
 *   <tr>
 *     <td>{@code RelationshipGraph}</td>
 *     <td>她<b>现在</b>与这个人是什么关系</td>
 *     <td>当前值(亲密度、信任、关系种类)</td>
 *   </tr>
 *   <tr>
 *     <td>本接口</td>
 *     <td>这段关系<b>怎么变成这样的</b></td>
 *     <td>按时间排列的状态变化</td>
 *   </tr>
 * </table>
 * 缺少本接口的后果很具体: {@code RelationshipGraph} 只有一个当前值, 于是
 * "她为什么最近对我冷淡了"这个问题<b>无法回答</b> —— 只能看到 0.4, 看不到
 * 它是从 0.8 掉下来的, 更看不到是上周三那次对话之后掉的。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不写当前值</b>。本接口只追加历史; 改当前值是 {@code RelationshipGraph} 的事。
 *       两边都能改的话, 两个值迟早不一致, 而不一致之后"哪个是真的"没有答案 ——
 *       这是这个代码库里 {@code CompanionAvailability} 类状态的老问题,
 *       不打算再犯一次。</li>
 *   <li><b>不做印象的生成</b>。"她是个爱迟到的人"这句话怎么来的, 是认知环节的输出;
 *       这里只负责把它记下来。</li>
 * </ul>
 */
public interface RelationshipMemory {

    /** 记下一次关系状态的变化。 */
    void record(MemoryRecord change);

    /** 与这个人之间关系变化的完整历史, 最早的在前。 */
    List<MemoryRecord> historyOf(PersonId person);

    /** 某段时间内的变化。 */
    List<MemoryRecord> historyOf(PersonId person, Instant from, Instant to);

    /** 最近一次变化 —— "我们的关系上一次因为什么动了"。 */
    Optional<MemoryRecord> lastChangeOf(PersonId person);

    /**
     * 她对这个人印象的一句话 —— 由认知环节写好之后存进来, <b>不是在这里生成的</b>。
     *
     * <p>它是"她心里对一个人的评价", 与 {@code PersonObject.impression()} 的区别是:
     * 那个是<b>她此刻会说的话</b>, 这个是<b>她曾经这么说过</b>的记录。
     */
    Optional<String> impressionNote(PersonId person);
}
