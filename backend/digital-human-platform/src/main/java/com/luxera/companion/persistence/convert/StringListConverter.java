package com.luxera.companion.persistence.convert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.luxera.companion.common.JsonCodec;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.util.ArrayList;
import java.util.List;

/**
 * V2.2 §7.2 —— <b>一列 {@code text} 装一个字符串列表</b>（{@code plan_item.dependencies} 等）。
 *
 * <h2>它和 {@code StringMapConverter} 是同一套约定的第二个成员</h2>
 * 本仓既有的做法是"一列 JSON = 一个 {@code AttributeConverter} + {@code columnDefinition = "text"}"
 * （见 {@code common/convert/StringMapConverter}、{@code persona/PersonaJsonConverter}）。
 * 本类只是把那个形状补到 {@code List<String>} 上, 而不是在 store 里手写
 * {@code JsonCodec.toJson(...)} —— 因为手写意味着<b>每一个读这一列的地方都要记得解一次</b>,
 * 而漏解的那一处不会报错, 它会得到一个 {@code String}, 然后在某个 {@code for} 循环里
 * 逐字符遍历那个 JSON 文本。
 *
 * <h2>为什么不用逗号分隔的一列 {@code VARCHAR}</h2>
 * 更简单, 但有两个具体的坏处:
 * <ul>
 *   <li>{@code PlanItemId} 现在长成 {@code item-42}, 看起来不会有逗号 —— 但"看起来"不是契约。
 *       某一天一个三方的 id 里带了逗号, 症状是"她的一项计划忽然多了一个依赖",
 *       而没有任何报错;</li>
 *   <li>逗号串无法表达"空列表"与"一个空字符串"的区别, 而 JSON 的 {@code []} 与 {@code [""]}
 *       可以。同一条纪律在 {@code AbstractActivity.CommonFields} 里出现过:
 *       <b>空与零不是一回事</b>。</li>
 * </ul>
 *
 * <h2>{@code null} 与空列表</h2>
 * 读出来是 {@code null}（列是 NULL）时返回 {@code null}, <b>不</b>偷偷换成空列表。
 * 理由: 本仓的实体若用 {@code List.of()} 兜底, 那么"这一行没写过依赖"与"这一行写了
 * 一个空依赖列表"在内存里长得一模一样 —— 而写入方区分这两者是有意义的
 * （前者是历史遗留行, 后者是"她想清楚了, 没有依赖"）。兜底由调用方显式做。
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        return attribute == null ? null : JsonCodec.toJson(attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        List<String> list = JsonCodec.fromJson(dbData, new TypeReference<List<String>>() {});
        return list == null ? null : new ArrayList<>(list);
    }
}
