package com.luxera.companion.person;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, String> {
    Optional<Person> findByUserId(String userId);
    Optional<Person> findByCompanionId(String companionId);
    List<Person> findByPersonType(String personType);

    /**
     * 账号ID 查重 —— 给用户看的"这个 ID 已经被占用了"用。
     *
     * <p>**不是**唯一性的保证(那是 {@code persons.handle} 上的唯一约束的事, 见
     * {@link Person#getHandle()}); 这里查一次只是为了让正常路径返回一句人话,
     * 而不是让用户收到一个数据库约束异常的翻译结果。
     */
    Optional<Person> findByHandle(String handle);

    /**
     * 还没有账号ID 的、**该有**账号ID 的人 —— 补号用, 见 {@code PersonHandleBackfill}。
     *
     * <p>带 {@code personTypeIn} 而不是 {@code findByHandleIsNull()}: OTHER 类型的 Person
     * 是数字人自己社交圈里的虚构人物(朋友/家人/同事), 它们**不是账号** —— 没有主人,
     * 不能被搜索, 也没有人会去改它们的名字。给它们发账号ID 只会造出一批无人认领的号码。
     */
    List<Person> findByHandleIsNullAndPersonTypeIn(Collection<String> personTypes);

    /** 一次取回多个 Agent 的 Person —— 列表页要显示每行的账号ID, 不能一行查一次 */
    List<Person> findByCompanionIdIn(Collection<String> companionIds);
}
