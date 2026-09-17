package com.luxera.companion.maintenance;

import com.luxera.companion.person.Handles;
import com.luxera.companion.person.Person;
import com.luxera.companion.person.PersonRepository;
import com.luxera.companion.person.PersonService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 把**存量** Agent 的账号ID 重铸成带 {@code agent_} 前缀的形状。
 *
 * <h2>为什么必须是一个新的 runner, 而不是改 {@code PersonHandleBackfill}</h2>
 *
 * 那个 runner 查的是 {@code handle is null} —— 它只给**没有号**的人补号, 对已经有一个
 * 10 位裸随机号的行一个字都不改。而存量 107 个 Agent **全都有号**(它们是账号ID 上线那天
 * 一起铸出来的), 只是形状不合新规。两件事的判据不同, 混在一个类里会让"补号"这个
 * 纯增量的操作突然开始**改写**已有数据 —— 那是完全不同的风险等级。
 *
 * <h2>为什么放 maintenance 包</h2>
 *
 * 与 {@code PersonHandleBackfill} / {@code GhostChatSweeper} 同理: 8092(openapi)的组件扫描
 * 白名单不含 {@code maintenance}, 于是本类**只在 8091 里存在**。两个进程同时做同一件写库的
 * 事没有意义, 而且 8092 的内存预算不该在启动时多背一次全表遍历。
 *
 * <h2>幂等与失败</h2>
 *
 * 判据是 {@code personType = AGENT 且 handle 不带前缀}。跑完之后这个查询返回空, 于是每次
 * 启动都是一个不写库的空转 —— 与 {@code PersonHandleBackfill} 逐字同构。某一条失败不中断
 * 其余(逐条 catch), 失败的会在下次启动时再试一遍。
 *
 * <h2>为什么用 Java 侧过滤, 而不是 SQL 的 {@code NOT LIKE 'agent_%'}</h2>
 *
 * 因为 {@code _} 在 LIKE 里是**单字符通配符**: {@code 'agent_%'} 会连 {@code agentX...}
 * 一起匹配。要写对得加 {@code ESCAPE} 子句, 而那种正确性是"看的人不觉得有问题、改的人
 * 一不留神就写错"的一类。改用 {@link Handles#isAgentHandle} 过滤, 判据与其它所有地方
 * **共用同一个定义**, 而且只有 107 行 —— 全表取回来在内存里过一遍的代价可以忽略。
 */
@Slf4j
@Component
public class AgentHandleMigration implements ApplicationRunner {

    private final PersonRepository persons;
    private final PersonService personService;

    public AgentHandleMigration(PersonRepository persons, PersonService personService) {
        this.persons = persons;
        this.personService = personService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // handle 为 null 的不归本类管 —— 那是 PersonHandleBackfill 的活, 而它现在会给
        // Agent 铸出带前缀的号(mintHandleFor 按类型分流)。两个 runner 各管一段, 判据不重叠。
        var stale = persons.findByPersonType(Person.TYPE_AGENT).stream()
                .filter(p -> p.getHandle() != null && !p.getHandle().isBlank())
                .filter(p -> !Handles.isAgentHandle(p.getHandle()))
                .toList();

        if (stale.isEmpty()) {
            return;
        }
        log.info("[AgentHandleMigration] {} 个 Agent 的账号ID 还是旧形状(无 {} 前缀), 开始重铸 —— "
                        + "它们仍然可用, 只是看不出「这个号不能改」",
                stale.size(), Handles.AGENT_PREFIX);

        int done = 0;
        int failed = 0;
        for (Person p : stale) {
            try {
                String before = p.getHandle();
                Person after = personService.remintAgentHandle(p.getId());
                // 完整的 old → new 逐条打出来, 而不是截断: 这就是这次迁移的审计输出 ——
                // 流水表里也有一份(person_handle_changes), 但那份要连库才看得到,
                // 而"哪个号变成了哪个号"正是迁移当天最可能被问到的问题。
                log.info("[AgentHandleMigration] {} → {}", before, after.getHandle());
                done++;
            } catch (Exception e) {
                failed++;
                log.warn("[AgentHandleMigration] {} ({}) 重铸失败(继续下一个): {}",
                        p.getId(), p.getHandle(), e.toString());
            }
        }

        if (failed == 0) {
            log.info("[AgentHandleMigration] 完成: {} 个 Agent 的账号ID 已带上 {} 前缀。"
                            + "这个日志只会在迁移那次出现 —— 之后这个查询每次启动都返回空。",
                    done, Handles.AGENT_PREFIX);
        } else {
            log.warn("[AgentHandleMigration] 完成: 成功 {} 个, 失败 {} 个。失败的会随下次启动自动重试。",
                    done, failed);
        }
    }
}
