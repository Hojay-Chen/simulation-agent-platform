package com.luxera.companion.human.mind.percept;

import java.util.Objects;

/**
 * V2.2 §3.4.3 —— <b>这次感知是"从哪儿来的"</b>, 但只说得出"哪个东西", 说不出"哪个人"。
 *
 * <h2>它与 {@code WorldEvent.sourceObjectId()} 的关系 (以及差别)</h2>
 * 事件上的 {@code sourceObjectId} 是<b>世界的 id</b> —— 一台设备、一件羽绒服、一个房间。
 * 它是字符串, 允许为空。这里的 {@link #objectId()} 就是它, 原样带过来。
 *
 * <p>差别在于多出来的两个字段:
 * <table border="1">
 *   <tr><th>字段</th><th>回答什么</th><th>为什么不能省</th></tr>
 *   <tr>
 *     <td>{@link #kind()}</td>
 *     <td>这是个什么东西</td>
 *     <td>没有它, {@code "phone-1"} 与 {@code "door-1"} 在她眼里没有区别,
 *         而"她自己手机的声音"和"门外有人"该有完全不同的处理</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #recognized()}</td>
 *     <td>她认不认得它</td>
 *     <td>V2.2 §3.4.3 的例子原话是"她认得出这是自己手机的声音" ——
 *         <b>这句话是她的知识, 不是世界的属性</b>, 所以它只能在这里</td>
 *   </tr>
 * </table>
 *
 * <h2>它<b>绝对不</b>装什么 —— 这条禁令是 §3.4.3 的具体落点</h2>
 * <b>这里没有聊天账号 id, 也没有发送者。</b>一个很容易犯、后果很重的错误是
 * 觉得"反正 source 要带个 id, 顺手把账号 id 塞进来, 后面省一次翻译"。
 * 那一步一旦发生:
 * <pre>
 *   // ❌ 一旦 source 能装下账号, 下面这种代码就写得出来了
 *   if (percept.source().accountId().equals(ownerAccount)) { 打断她; }
 * </pre>
 * 于是"是谁发的"这个问题被 <b>Perception</b> 回答了 —— 而 §3.4.3 明写它属于
 * <b>Relationship</b>。后果不是崩溃, 是更坏的: 她会在<b>还没读到任何内容</b>的时候
 * 就知道"这是主人发来的", 于是"她先注意到了才决定去看"这条因果链断了一环,
 * 行为分析里再也分不清"她被消息内容打动"和"她看到名字就点开了"。
 *
 * <p>账号 → 人的翻译只发生在一个地方: {@code RelationshipGraph.resolve}.
 *
 * <h2>{@code kind} 为什么是字符串而不是 enum</h2>
 * 按 §1.3 P4 那条判据: 世界上有多少种物体? 无穷。第三方接一台"宠物喂食器",
 * 就多一种 kind。所以它不是 enum —— 它甚至比事件类型更开放, 因为事件至少还有
 * 平台自带的 43 条目录, 而物体种类连目录都没有。几个常用值作为常量给出,
 * 只是为了让人别写出 {@code "PHONE"} / {@code "phone"} / {@code "Phone"} 三种拼法。
 */
public record SourceRef(String objectId, String kind, String label, boolean recognized) {

    /** 她自己的身体 —— 内脏感受、疼痛、饥饿都从这里来。 */
    public static final String KIND_SELF_BODY = "self-body";

    /** 她自己的设备 —— 认得出来的那一台(通常就是她的手机)。 */
    public static final String KIND_OWN_DEVICE = "own-device";

    /** 别人的东西 / 公共设施。 */
    public static final String KIND_FOREIGN = "foreign";

    /** 说不上来是什么 —— 刺激从哪儿来不知道。 */
    public static final String KIND_UNKNOWN = "unknown";

    public SourceRef {
        Objects.requireNonNull(kind, "来源必须有一个类别 —— 没有类别的来源无法被描述。"
                + "确实不知道时请用 KIND_UNKNOWN, 但请先想一想为什么不知道");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("来源类别不能是空白串");
        }
        label = label == null ? "" : label;
    }

    /** 她说不出这是什么东西。用于 {@code sourceObjectId} 为空的事件。 */
    public static SourceRef unknown() {
        return new SourceRef(null, KIND_UNKNOWN, "说不上来是哪儿", false);
    }

    /** 她自己的身体。 */
    public static SourceRef selfBody(String label) {
        return new SourceRef("self", KIND_SELF_BODY, label, true);
    }

    /** 她认得出来的那台自己的设备。 */
    public static SourceRef ownDevice(String objectId, String label) {
        return new SourceRef(objectId, KIND_OWN_DEVICE, label, true);
    }

    /** 认不出来的东西。 */
    public static SourceRef unfamiliar(String objectId, String label) {
        return new SourceRef(objectId, KIND_FOREIGN, label, false);
    }

    /**
     * 照搬一个世界对象 id, 但<b>不声称认识它</b>。
     *
     * <p>这是 {@link Perception} 在没有额外词汇表时的默认行为, 也是刻意的保守选择:
     * "她认得这是自己的手机"应当由<b>知道这件事的人</b>显式说出来(构造
     * {@link #ownDevice}), 而不是由 Perception 猜。猜的后果是"陌生的响声"
     * 被当成"她的手机", 而这两者在注意力上是完全不同的两件事。
     */
    public static SourceRef ofObject(String objectId) {
        return objectId == null || objectId.isBlank()
                ? unknown()
                : new SourceRef(objectId, KIND_FOREIGN, objectId, false);
    }

    public boolean hasObject() {
        return objectId != null && !objectId.isBlank();
    }

    public String describe() {
        String what = label.isEmpty() ? kind : label;
        return what + (hasObject() ? "(" + objectId + ")" : "")
                + (recognized ? " [认得]" : "");
    }

    @Override
    public String toString() {
        return describe();
    }
}
