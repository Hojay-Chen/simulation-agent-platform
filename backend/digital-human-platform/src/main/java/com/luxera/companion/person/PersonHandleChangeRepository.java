package com.luxera.companion.person;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号ID 修改流水, 见 {@link PersonHandleChange}。
 *
 * <p>查询形状固定成"某个人的、某个时刻之后的"两种: 配额要用前者({@code count}),
 * 界面要显示"下次能改是哪天"要用后者({@code find})。**没有**"列出全部流水"的方法 ——
 * 那是审计需求, 现在没有这个需求, 加了就是一个谁都不敢删的公开接口。
 */
public interface PersonHandleChangeRepository extends JpaRepository<PersonHandleChange, String> {

    /** 窗口内改了几次 —— 配额判定的唯一依据 */
    long countByPersonIdAndChangedAtAfter(String personId, LocalDateTime after);

    /** 窗口内的流水, 最近的在前 —— 用来算"下次能改是哪天" */
    List<PersonHandleChange> findByPersonIdAndChangedAtAfterOrderByChangedAtDesc(
            String personId, LocalDateTime after);
}
