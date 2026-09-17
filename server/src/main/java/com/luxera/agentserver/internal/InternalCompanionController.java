package com.luxera.agentserver.internal;

import com.luxera.companion.person.PersonService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 补铸与对账用的服务间端点 —— 聊天平台问"哪些 Agent 还没有聊天账号", 以及"把这个账号记上"。
 *
 * <h2>与 {@link InternalCompanionDirectoryController} 的分工</h2>
 *
 * 那一个是**认知链**要的: 落一条消息前的归属校验、落完之后的通知。它服务的对象是
 * {@code CompanionDirectoryPort}, 也就是"聊天平台操作这个 Agent"的那条路。
 *
 * <p>这一个是**身份**要的: 一个 Agent 在聊天平台上叫什么。它服务的对象是补铸 runner 与
 * 对账 runner —— 两个一次性的、把历史数据补齐的活。两者没有一行代码重叠, 所以不合并,
 * 也正因为不合并, 关掉补铸不会顺手把归属校验一起关掉。
 *
 * <h2>为什么两条路都是 POST, 尽管其中一条明明只是在读</h2>
 *
 * 因为签名覆盖的是 {@code <timestamp>.<body>}, **查询串不在签名里**。一个
 * {@code GET /internal/companions?missingChatAccount=true} 的语义全部落在那个未被签名的
 * 参数上: 能改这个参数的人就能改这次调用的含义, 而签名照样通过。这条路的输出直接决定
 * 哪些 Agent 会被铸账号 —— 这不是可以"签了个空串"就放过的形状。
 * (同理, 补铸那条路不用 {@code /{companionId}/...} 路径变量: 路径同样在签名之外。)
 *
 * <p>内网 + HMAC 的威胁模型下这条也许永远不会被利用, 但把语义放进签名材料的代价是零,
 * 而"签名没覆盖真正重要的那个字节"这类缺陷没有任何症状 —— 它只会在事后审计时才被发现。
 */
@RestController
@RequestMapping("/internal/companions")
public class InternalCompanionController {

    private final CompanionService companionService;
    private final PersonService personService;

    public InternalCompanionController(CompanionService companionService,
                                       PersonService personService) {
        this.companionService = companionService;
        this.personService = personService;
    }

    /**
     * 列活着的 Agent —— 补铸 runner 的输入。
     *
     * <p>{@code missingChatAccount} 两个值各服务一个 runner, 不是"一个方便参数":
     * <ul>
     *   <li>{@code true} —— <b>补铸</b>: 还没有聊天账号的那些。</li>
     *   <li>{@code false} —— <b>对账</b>: 已经记了账号的那些, 拿去和聊天平台的
     *       {@code simulator_devices} 逐条比。少了这一支, 对账就不得不先问
     *       "全部 Agent"再在调用方那边过滤, 而"全部"包含已删除的与从未铸过号的,
     *       那两类的判据与对账完全无关。</li>
     * </ul>
     *
     * <p>{@code handle} 一次性批量取({@code handlesOfCompanions} 一条 {@code IN} 查询),
     * 不是逐行查 —— 这是列表接口, 逐行取就是 N+1。带着它是因为补铸跑完人会去看日志,
     * 而"哪个 {@code agent_xxx} 配上了账号"是唯一能拿去和界面上那串对上的值。
     */
    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody ListBody body) {
        boolean missingOnly = body != null && body.missingChatAccount();
        List<Companion> found = missingOnly
                ? companionService.listMissingChatAccount()
                : companionService.listLive();

        List<String> ids = new ArrayList<>(found.size());
        for (Companion c : found) ids.add(c.getId());
        Map<String, String> handles = personService.handlesOfCompanions(ids);

        List<Item> items = new ArrayList<>(found.size());
        for (Companion c : found) {
            items.add(new Item(c.getId(), c.getName(), c.getChatAccountId(), handles.get(c.getId())));
        }
        return Map.of("companions", items, "count", items.size());
    }

    /**
     * 把一个已经存在的聊天账号登记给一个已经存在的 Agent。
     *
     * <p>404 = Agent 不存在或已删除。409 = 这个 Agent 已经有别的账号, 或这个账号已经归了
     * 别的 Agent。**同一个值重放返回 200** —— 补铸 runner 是可重跑的, 第二次跑必然命中
     * 这种情况, 它必须成功(见 {@code CompanionService#attachChatAccount})。
     *
     * <p>返回值里带上 {@code handle}, 理由与 {@link #list} 那一处相同: 日志里要能对上人眼。
     */
    @PostMapping("/attach-chat-account")
    public Item attachChatAccount(@RequestBody AttachBody body) {
        Companion c = companionService.attachChatAccount(body.companionId(), body.chatAccountId());
        return new Item(c.getId(), c.getName(), c.getChatAccountId(),
                personService.handleOfCompanion(c.getId()));
    }

    /**
     * 刻意只有这四个字段 —— 每个字段都是**判据或日志**, 没有一个是"顺便带上"的。
     *
     * <p>不带上 {@code userId}: 乍看它很有用("这个 Agent 归谁"), 但聊天平台在补铸里**不用**
     * 它 —— 铸出来的 SIMULATOR 账号不属于那个真人, 也不该属于他。给了它, 迟早会有人拿它去
     * 决定"给谁铸号", 而那正是把两个平台的身份重新搅在一起的第一步。
     *
     * <p>也不带任何人格/关系/状态: 补铸**一个字都不改**, 它只写一列。
     *
     * <p>{@code chatAccountId} 与 {@code handle} 都是**可空**的, 而且必须让调用方看得出是
     * 空 —— 所以是 {@code null} 而不是 {@code ""}。空串在这里是个陷阱: 调用方一个
     * {@code isBlank()} 写漏了就会把"没有账号"当成"账号是空字符串"照单全收。
     */
    public record Item(String companionId, String name, String chatAccountId, String handle) {}

    /** {@code missingChatAccount} 缺省为 {@code false} —— 见类注释: 显式是刻意的。 */
    public record ListBody(boolean missingChatAccount) {}

    public record AttachBody(String companionId, String chatAccountId) {}
}
