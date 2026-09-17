package com.luxera.companion.person;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 真人改自己的账号ID —— {@code /api/persons/me/handle}。
 *
 * <h2>为什么是 "me", 而不是 {@code /{personId}/handle}</h2>
 *
 * 因为**没有第二个参数可以填错**。{@code /api/persons/{id}/handle} 那种形状要求服务端
 * 自己回答"路径里这个 id 是不是调用者本人", 而那个判断写漏一次就是"任何人都能改任何人的
 * 账号ID"。这里 id 直接来自已认证的 SecurityContext({@link CurrentUser#requireUserId()}),
 * 调用方**没有机会**指定改谁 —— 越权在这个形状下不是一个需要被检查的错误, 而是一个
 * 无法被表达的操作。
 *
 * <h2>为什么这个端点住在 8091, 而不是 8081 自己实现</h2>
 *
 * 因为 {@code persons} 表**只有本仓有实体映射**。8081 若自己实现它, 就必须以服务身份调
 * 8091 并转达一个 userId —— 那正是 {@code CompanionDomainProxyController} 的类注释明确
 * 拒绝的 confused deputy 形状("任何能过 8081 鉴权的请求, 都能借服务身份读到别人的伴侣")。
 * 由 8081 把**调用者的 JWT 原样转发**到 8091(与 {@code /api/companions/**} 完全同一条路),
 * 8081 就不新增任何授权判断, 判断在持有所需数据的一侧做。
 *
 * <h2>为什么没有"改 Agent 的号"的对应端点</h2>
 *
 * 因为 Agent 的聊天账号ID 由系统分配、不可修改。那条规则在
 * {@link PersonService#changeHandle} 里由类型闸门强制执行 —— 端点不存在只是第二道防线,
 * 真正的保证在 service 层(否则任何一个新加的调用点都能绕过它)。
 */
@RestController
@RequestMapping("/api/persons/me/handle")
public class PersonController {

    private final PersonService personService;
    private final CurrentUser currentUser;

    public PersonController(PersonService personService, CurrentUser currentUser) {
        this.personService = personService;
        this.currentUser = currentUser;
    }

    /** 我现在的账号ID + 配额现状 —— 设置页打开时读一次。 */
    @GetMapping
    public CompanionDtos.HandleView mine() {
        return CompanionDtos.HandleView.of(
                personService.quotaOf(personService.getOrCreateUser(currentUser.requireUserId()).getId()));
    }

    /**
     * 改我自己的账号ID。
     *
     * <p>校验失败分别报 400(形状)/409(被占用)/429(配额用尽), 前端据此给不同的提示 ——
     * 三者对用户来说是三件不同的事, 合成一个"修改失败"等于让用户自己猜该改什么。
     */
    @PutMapping
    public CompanionDtos.HandleView change(@RequestBody CompanionDtos.UpdateHandleRequest req) {
        return CompanionDtos.HandleView.of(
                personService.changeUserHandle(currentUser.requireUserId(), req.getHandle()));
    }
}
