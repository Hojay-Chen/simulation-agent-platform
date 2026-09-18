package com.luxera.companion.human.mind.percept;

import com.luxera.companion.boundary.event.SensoryEvent;

import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §3.4.3 —— <b>她把自己感觉到的东西说成一句人话时用的那本词表</b>。
 *
 * <h2>为什么这件事必须是一个可替换的接口, 而不是 Perception 里的一段 if</h2>
 * {@link Percept#content()} 会进 LLM context, 所以它必须是
 * <pre>
 *   ✅ "手机发出了一声提示音"
 *   ❌ "device.phone.notification-raised.v1 via auditory urgency=0.68"
 * </pre>
 * 后一种写法能编译、能跑、也能进 context —— 它只是让模型和读日志的人都得不到信息。
 * 于是"措辞"这件事必须有人负责。
 *
 * <p>那么谁来负责? <b>不能是核心包。</b>因为"手机发出提示音"这句话里
 * 已经含有一个具体设备的知识, 而架构守卫
 * ({@code humanAndWorldKnowNothingAboutAnyParticularPlatform}) 明令禁止
 * {@code human/} 里出现任何具体平台的名字、action id、资源 URI 前缀。
 * 一个把设备名写死在 Perception 里的实现, 会在下一个人接入第二台设备时
 * 逼着他去改核心包 —— 而 V2.2 的全部承诺就是"接入一台新设备, 核心代码一行不改"。
 *
 * <h2>于是措辞<b>是数据, 不是代码</b></h2>
 * 词表由知道那些词的人提供 —— 世界侧的设备实现、应用实现、或者 agent 的 persona。
 * 核心包只给一个不携带任何设备知识的默认实现({@link #generic()}), 它说的话是
 * "听觉上有了一次刺激"这种<b>真但贫瘠</b>的句子。贫瘠是可接受的; 说谎(把陌生的响声
 * 说成"她的手机")不可接受。
 *
 * <h2>它不负责什么</h2>
 * 它<b>不</b>决定显著度, 也<b>不</b>决定要不要注意。它只负责把类型 id 变成一句人话。
 * 一个词表实现如果开始返回"这条消息很重要"这样的句子, 那是把 Attention 的活
 * 抢到了措辞层 —— 而措辞层是在<b>没有</b>她处境信息的情况下被调用的, 所以它给不出
 * 这个判断, 只会给出一个假装的判断。
 */
@FunctionalInterface
public interface PerceptLexicon {

    /**
     * 把这个刺激说成一句人话。
     *
     * <p>实现应当<b>只依赖事件本身</b>(类型、通道、紧迫度), 不依赖外部状态 ——
     * 一个会随时间变化的词表会让同一条刺激在不同时刻得到不同的说法,
     * 而"她感知到了什么"必须是可复现的。
     */
    String describe(SensoryEvent event);

    /**
     * 默认词表 —— 不带任何具体设备/平台知识。
     *
     * <p>它说的话很平淡, 但它<b>从不撒谎</b>: 类型 id 是平台自己给这条刺激起的名字,
     * 把它原样说出来不会把陌生的响声说成"她的手机"。接入方要更好的措辞,
     * 就该提供自己的词表, 而不是让核心包去猜。
     */
    static PerceptLexicon generic() {
        return event -> {
            Modality modality = Modality.fromChannel(event.modality());
            if (modality == Modality.UNKNOWN) {
                return "有个说不清是什么的感觉(" + event.typeId() + ")";
            }
            return modality.label() + "上有了一次刺激(" + event.typeId() + ")";
        };
    }

    /**
     * 按类型 id 查表的词表。
     *
     * <p>表里没有的类型走 {@link #generic()} —— 落回一个真但贫瘠的句子,
     * 而不是抛异常。<b>这不是宽容, 是可用性</b>: 一台新设备第一天上线的正确行为是
     * "她感觉到了但说不出是什么", 而不是"她的整个感知链路炸了"。
     */
    static PerceptLexicon of(Map<String, String> phrasesByTypeId) {
        Objects.requireNonNull(phrasesByTypeId, "词表不能为 null —— 没有词条请传空 Map");
        Map<String, String> frozen = Map.copyOf(phrasesByTypeId);
        PerceptLexicon fallback = generic();
        return event -> {
            String phrase = frozen.get(event.typeId().toString());
            return phrase == null || phrase.isBlank() ? fallback.describe(event) : phrase;
        };
    }

    /**
     * 先问这一本, 没有词条再问 {@code fallback}。
     *
     * <p>存在的理由是装配: 设备方提供设备词表, persona 提供人物词表,
     * 两者谁也不知道对方有哪些词条, 而它们不该被合并成一个大 Map
     * (合并之后"哪一条覆盖了哪一条"取决于装配顺序, 于是同一份配置在两次启动里
     * 可能给出不同的说法)。
     */
    default PerceptLexicon orElse(PerceptLexicon fallback) {
        Objects.requireNonNull(fallback, "后备词表不能为 null");
        PerceptLexicon self = this;
        return event -> {
            String phrase = self.describe(event);
            if (phrase != null && !phrase.isBlank() && !isGenericPhrase(phrase)) {
                return phrase;
            }
            return fallback.describe(event);
        };
    }

    /**
     * 一句话看起来是不是"默认词表说的"。
     *
     * <p>判据是那个固定的前缀形状 —— 它由 {@link #generic()} 唯一产出,
     * 所以不会误伤接入方自己写的话。
     */
    private static boolean isGenericPhrase(String phrase) {
        return phrase.endsWith(")") && (phrase.startsWith("有个说不清是什么的感觉(")
                || phrase.contains("上有了一次刺激("));
    }
}
