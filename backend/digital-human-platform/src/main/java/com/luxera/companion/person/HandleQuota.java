package com.luxera.companion.person;

import java.time.LocalDateTime;

/**
 * 「账号ID 还能改几次、下次什么时候能改」—— 界面上要显示的那两个数。
 *
 * <p>单独成一个类型, 而不是让 controller 从 service 里取两次:
 * <ul>
 *   <li>两个数是**同一个判定**的两面(改满三次 ⇒ 才有"下次可改时间"), 分开取会有中间态 ——
 *       用户可能看到"还能改 1 次"旁边写着"下次可改: 2027-03-01"这种自相矛盾的组合。</li>
 *   <li>它是**给用户看的**, 所以语义要说人话: {@code remaining} 是"还能改几次",
 *       不是"已用次数" —— 后者让每个调用点自己去做减法, 而减法总有做反的。</li>
 * </ul>
 *
 * @param handle     当前账号ID
 * @param used       最近 365 天内已改次数
 * @param limit      上限(每年 3 次)
 * @param remaining  {@code limit - used}, 至少为 0
 * @param nextChangeAt 额度用尽时, 最早能再改的时刻; 还有额度时为 {@code null}
 */
public record HandleQuota(String handle, int used, int limit, int remaining,
                          LocalDateTime nextChangeAt) {

    public static HandleQuota of(String handle, int used, int limit, LocalDateTime nextChangeAt) {
        int remaining = Math.max(0, limit - used);
        return new HandleQuota(handle, used, limit, remaining,
                remaining > 0 ? null : nextChangeAt);
    }
}
