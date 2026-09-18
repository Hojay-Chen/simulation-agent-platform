package com.luxera.companion.human.mind.memory;

import com.luxera.companion.human.mind.relationship.PersonId;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §3.4.1 —— <b>共享记忆: "我和她/他一起经历过的"</b>。
 *
 * <h2>它为什么必须单独存在</h2>
 * 因为它记的<b>不是她一个人的事</b>。一次一起吃饭的经历, 单独放在情景记忆里,
 * 就只剩"她吃了饭"这一半 —— 而关系里真正起作用的是另一句:
 * <b>"我们一起吃过饭"</b>。这两个说法在计算上是不同的: 前者可以被遗忘而不伤及关系,
 * 后者一旦丢掉, {@code RelationshipGraph} 就不再知道他们为什么亲近。
 *
 * <p>这也是它与关系记忆的分工: 共享记忆记的是<b>事件</b>("那次晚餐"),
 * 关系记忆记的是<b>状态</b>("我很信任她")。事件会被遗忘, 状态是被事件改变的。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不保存双方各自的版本</b>。她是 agent, 对面是真人 ——
 *       我们只能记录<b>她这一侧</b>的记忆。"他也记得"是一个我们无从验证的断言,
 *       而把无从验证的东西写进记忆是仿真里最容易积累的假数据。</li>
 *   <li><b>不决定关系</b>。一次不愉快的共享经历会降低亲密度, 但那个计算在
 *       {@code RelationshipGraph} 里。在这里顺手改一下亲密度, 会让关系变化
 *       散落在两个地方, 而"她为什么疏远了"再也没法从一个地方回答。</li>
 * </ul>
 */
public interface SharedMemory {

    /** 记下一件"我们一起做的事"。{@code record} 的 subject 应当是那个人的 id。 */
    void share(MemoryRecord record);

    /** 她与这个人之间发生过的全部共享记忆, 最新的在前。 */
    List<MemoryRecord> with(PersonId person, int limit);

    /** 某一段时间里与这个人共有的经历。 */
    List<MemoryRecord> with(PersonId person, java.time.Instant from, java.time.Instant to);

    /** 她与这个人共有多少件事 —— "我们很熟"的一个粗粒度代理指标。 */
    int sharedCount(PersonId person);

    /** 最近一次共同的经历。用于"上次我们聊到哪儿了"。 */
    Optional<MemoryRecord> lastWith(PersonId person);
}
