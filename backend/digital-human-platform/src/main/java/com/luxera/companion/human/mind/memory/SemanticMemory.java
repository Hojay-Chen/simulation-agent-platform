package com.luxera.companion.human.mind.memory;

import java.util.List;
import java.util.Set;

/**
 * V2.2 §3.4.1 —— <b>语义记忆: "我知道的那些事实"</b>。
 *
 * <h2>它与情景记忆的差别不是抽象程度, 是"有没有发生的时刻"</h2>
 * "她昨天下午三点读到图书馆九点关门"(情景) 与 "图书馆九点关门"(语义)
 * 是两件不同的事。前者她会说"我昨天看到的", 后者她会说"我知道"。
 * 这个差别在实现上是具体的: 情景记忆可以不记录被遗忘的细节, 而语义记忆
 * <b>一旦记下就不该丢</b> —— 一个人不会"有点记得图书馆的关门时间"。
 *
 * <h2>冲突怎么办 —— 这是本接口唯一容易做错的地方</h2>
 * 她先知道"图书馆九点关门", 后来知道改成八点了。这不是两条并列的事实,
 * 而是<b>一条被修正的事实</b>。所以 {@link #assertFact} 的语义是<b>覆盖同一线索的旧值</b>,
 * 而不是追加。追加的实现会让"图书馆几点关门"这个问题有两三个答案,
 * 而下一次检索到哪一个取决于顺序 —— 那是最难查的一类错误。
 *
 * <p>旧值不被删除, 而是标记为被取代(见 {@link FactRevision}), 于是
 * "她什么时候改的口径"仍然可查。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不判断真假</b>。她"以为"的事实也是她的记忆。世界侧另有一套事实,
 *       两者的差正是"她搞错了"这件事的落点。</li>
 *   <li><b>不做推理</b>。"图书馆关门了所以现在去不了"是认知环节的事。</li>
 * </ul>
 */
public interface SemanticMemory {

    /**
     * 记下/修正一个事实。
     *
     * @param cue  这个事实的检索线索, 例如 {@code "library.closing-time"}。
     *             同名线索的值会被覆盖, 并留下一条修订记录。
     * @return 被这条覆盖掉的旧值; 没有旧值时为空
     */
    java.util.Optional<String> assertFact(String cue, String value, MemoryRecord source);

    /** 按线索取当前的值。 */
    java.util.Optional<String> fact(String cue);

    /** 线索命中任一项的事实, 按重要度降序。 */
    List<MemoryRecord> search(Set<String> cues, int limit);

    /** 一个线索上的完整修订链, 最早的在前 —— "她什么时候改的口径"。 */
    List<FactRevision> revisions(String cue);

    /** 全部线索。 */
    Set<String> cues();

    /**
     * 一次修正: 某个线索的值从 {@code previous} 变成了 {@code current}。
     *
     * <p>{@code previous} 为空表示这条线索的第一次记录 ——
     * 第一次与修正必须是可区分的, 否则"她改主意了"与"她刚知道"在分析里没有差别。
     */
    record FactRevision(String cue, String previous, String current,
                        java.time.Instant recordedAt) {

        public FactRevision {
            if (cue == null || cue.isBlank()) {
                throw new IllegalArgumentException("修订必须给出线索 —— 没有线索的修订无法被检索到");
            }
            previous = previous == null ? "" : previous;
            current = current == null ? "" : current;
            if (recordedAt == null) {
                throw new IllegalArgumentException("修订必须带时刻 —— 不许读系统时钟");
            }
        }

        /** 这是不是第一次知道这件事。 */
        public boolean firstTime() {
            return previous.isEmpty();
        }

        public String describe() {
            return firstTime()
                    ? "第一次知道 " + cue + " = " + current
                    : cue + ": " + previous + " → " + current;
        }
    }
}
