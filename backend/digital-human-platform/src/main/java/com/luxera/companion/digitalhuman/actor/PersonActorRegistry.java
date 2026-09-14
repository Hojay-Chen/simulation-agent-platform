package com.luxera.companion.digitalhuman.actor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §20 Person Actor Registry: PersonId → PersonActor 的注册表。
 *
 * 提供 tell(personId, task) 统一入口: 任务进入该 Person 的 mailbox,
 * 由 Actor 单线程串行执行 —— 全系统唯一的 Person 状态写入口。
 * 不同 Person 的 Actor 互不阻塞(并行)。
 */
@Slf4j
@Component
public class PersonActorRegistry {

    private final Map<String, PersonActor> actors = new ConcurrentHashMap<>();
    private final Map<String, Object> perPersonLocks = new ConcurrentHashMap<>();

    /** 提交任务到指定 Person 的 mailbox(异步, 严格 FIFO 串行执行) */
    public void tell(String personId, Runnable task) {
        PersonActor actor = actors.get(personId);
        if (actor == null || !actor.alive()) {
            // 空闲回收后重建: 先清死引用, 再 putIfAbsent(并发提交竞争安全)
            actors.remove(personId, actor);
            PersonActor fresh = new PersonActor(personId, t ->
                    log.error("[PersonActor] {} 任务异常: {}", personId, t.getMessage()));
            PersonActor existing = actors.putIfAbsent(personId, fresh);
            actor = existing != null ? existing : fresh;
        }
        actor.tell(task);
    }

    /** 获取(或创建)某 Person 的 actor */
    public PersonActor actorOf(String personId) {
        return actors.computeIfAbsent(personId, k -> new PersonActor(k, null));
    }

    /** 同步互斥锁(同一 Person 的同步临界区; Actor 内任务无需再持锁) */
    public Object lockOf(String personId) {
        return perPersonLocks.computeIfAbsent(personId, k -> new Object());
    }

    public int size() {
        return actors.size();
    }

    /** 关闭所有 actor(应用停机时调用) */
    public void shutdownAll() {
        actors.values().forEach(PersonActor::shutdown);
        actors.clear();
    }
}
