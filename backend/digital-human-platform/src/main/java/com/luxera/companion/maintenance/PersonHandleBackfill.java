package com.luxera.companion.maintenance;

import com.luxera.companion.person.Handles;
import com.luxera.companion.person.Person;
import com.luxera.companion.person.PersonRepository;
import com.luxera.companion.person.PersonService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 给**加列之前就存在**的 User / Agent 补上账号ID。
 *
 * <h2>为什么必须有, 而不是"新代码会带上就行"</h2>
 *
 * 账号ID 是 2026-09-17 新加的一列, 而那一天库里已经有 47 个活着的 Agent 和若干用户。
 * 新代码只保证**新建**的人带号 —— 用户看到的却是他那 9 个已经存在的 Agent(其中 7 个
 * 都叫「小满」)。不补号, 这一列对老数据永远是 null, 界面上没有任何变化, 功能等于没做。
 *
 * <p>为什么不能像 {@code GhostChatSweeper} 那样挂在一个开关后面: 那个开关后面是不可逆的
 * 硬删除, 跑之前必须有人点一次头。补号是**纯增量**的 —— 它只给 null 填上值, 不改任何
 * 已有数据, 也不删任何东西, 最坏的结果是"多了一个没人认领的号码"。这种操作不该要求
 * 运维记得开一次开关, 忘了的后果是功能静默失效。
 *
 * <h2>为什么放在 maintenance 包(而不是 person 包)</h2>
 *
 * 8092(openapi)的组件扫描白名单只覆盖 {@code persona/relationship/person/state/llm} 的
 * 几个薄件, {@code maintenance} 不在其中 —— 于是本类**只在 8091 里存在**。补号只需要
 * {@code PersonService}, 在 8092 里跑也不是不行, 但两个进程同时做同一件写库的事没有意义,
 * 而且 8092 的内存预算是 320 MB, 不该在启动时多背一次全表遍历。
 *
 * <h2>幂等与失败</h2>
 *
 * 每次启动都跑, 但它查的是 {@code handle is null} —— 补完之后这个查询返回空, 于是一个
 * 不写库的空转。补的过程中某一条失败不会中断其余(逐条 catch), 因为几十个里有一个坏掉的
 * 不该让其他人的账号ID 一起缺失; 失败的会在下次启动时再试一遍。
 */
@Slf4j
@Component
public class PersonHandleBackfill implements ApplicationRunner {

    private final PersonRepository persons;
    private final PersonService personService;

    public PersonHandleBackfill(PersonRepository persons, PersonService personService) {
        this.persons = persons;
        this.personService = personService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 只给"该有账号"的两类补。OTHER 是数字人自己社交圈里的虚构人物, 不是账号 ——
        // 给它发号会造出一批无人认领、也无法被搜索到的号码, 见 PersonRepository 的同款注释。
        List<Person> missing = persons.findByHandleIsNullAndPersonTypeIn(
                List.of(Person.TYPE_USER, Person.TYPE_AGENT));
        if (missing.isEmpty()) {
            return;
        }
        log.info("[HandleBackfill] {} 个已存在的账号还没有账号ID, 开始补 —— "
                + "它们是加列之前建的, 新代码只保证新建的带号", missing.size());

        int done = 0;
        int failed = 0;
        for (Person p : missing) {
            try {
                personService.ensureHandle(p);
                done++;
            } catch (Exception e) {
                failed++;
                log.warn("[HandleBackfill] {} ({}) 补号失败(继续下一个): {}",
                        p.getId(), p.getPersonType(), e.toString());
            }
        }

        if (failed == 0) {
            log.info("[HandleBackfill] 完成: {} 个账号已补上账号ID ({})。这个日志只会在"
                    + "首次部署后出现一次 —— 之后这个查询每次启动都返回空。",
                    done, Handles.class.getSimpleName());
        } else {
            log.warn("[HandleBackfill] 完成: 成功 {} 个, 失败 {} 个。失败的会随下次启动自动重试。",
                    done, failed);
        }
    }
}
