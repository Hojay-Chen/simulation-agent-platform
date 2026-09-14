package com.luxera.companion.tool;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 提醒的对外形状 —— <b>DTO, 不再是实体</b>(LAP v1 R5)。
 *
 * <p>它曾经是一张 {@code reminders} 表的映射, 数字人因此成了提醒的<em>所有者</em>: 谁写谁读都在
 * 这边, 于是"提醒"这件事有两份真相 —— 数字人这张表, 和用户装的那个提醒应用。现在只剩一份:
 * 提醒住在 {@code com.luxera.reminder} 的 {@code reminder_item} 表里, 这个类只是把应用给出的
 * {@code ResourceView.state()} 翻译成前端与聊天流程一直在用的那几个字段。
 *
 * <p>字段名一个没改(<b>含 {@code content} 对应应用的 {@code note}、{@code remindAt} 对应
 * {@code dueAt}</b>)—— 翻译只发生在 {@link ReminderService} 里, 前端 {@code Reminder} 类型与
 * {@code /api/companions/{id}/reminders} 的 JSON 形状因此完全不变。
 *
 * <p>{@code status} 用的是数字人这边的词汇({@code pending / done / cancelled}), 不是应用的
 * {@code PENDING / DISPATCHED / DONE / CANCELLED}。两套词汇的换算同样只在
 * {@link ReminderService} 一处发生 —— 前端里那一行 {@code r.status === 'done'} 不该因为
 * 应用改用大写而失效。
 */
@Getter
@Setter
public class Reminder {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_CANCELLED = "cancelled";

    private String id;
    private String userId;
    private String companionId;
    private String type;
    private String title;
    /** 应用里叫 {@code note} —— 名字不同的同一个东西。 */
    private String content;
    /** 应用里叫 {@code dueAt}。 */
    private LocalDateTime remindAt;
    private String status = STATUS_PENDING;
    private LocalDateTime createdAt;
}
