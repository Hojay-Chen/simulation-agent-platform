package com.luxera.agentserver.web;

import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用平台(LAP)目录的**只读出口** —— 给 Being Studio 的「技能」页用。
 *
 * <h2>它为什么存在</h2>
 *
 * 认知链一直在问 LAP "有什么能力、哪个应用能做、它有哪些动作"
 * （{@link ApplicationRuntimePort} 的三个读方法, 走 HMAC 到聊天平台)。但这条链
 * **只有入口, 没有出口**: 三个读方法被认知链消费, 却没有任何控制器暴露过它们,
 * 于是控制台上一整块(§19 的 Applications / Skills)在数据上其实一直都在, 只是
 * 没人能看见。这个类就是那条出口 —— 一行业务逻辑都没有, 全部工作是把三个端口
 * 调用拼成一棵能一次返回的树。
 *
 * <h2>为什么拼成一棵树, 而不是三个直通端点</h2>
 *
 * 三层直通(能力 → 应用 → 动作)会让前端写一个两层循环, 打 1+N 次请求; 而 LAP 的
 * 应用数量本来就少, 一次取全反而更快也更简单。**这是一份读模型, 不是端口的镜像** ——
 * 端口的粒度是给认知链的(它要先定能力再挑应用), 控制台要的是"这个平台一共能做什么"。
 *
 * <h2>8081 不可达时会怎样</h2>
 *
 * 端口自己的契约是"读缺席 → 空", 于是这里得到空列表, 页面显示"应用平台现在没有
 * 登记任何能力"。**这个说法在"平台真的没登记"与"聊天平台连不上"两种情况下字面相同**
 * —— 刻意如此: 这个页面没有能力区分二者(它拿不到连接层的错误), 与其编一句
 * "服务可能挂了"的猜测, 不如说清楚它知道的那件事。真正的连通性判断在 System 页。
 *
 * <p>鉴权: 落在 {@code /api/**}, 由 {@code ServerSecurityConfig} 的
 * {@code anyRequest().authenticated()} 要求用户 JWT —— 与其余内省接口同门。
 * 目录是平台级的(不属于某一个 agent), 所以它没有 companionId 参数。
 */
@Slf4j
@RestController
@RequestMapping("/api/lap")
public class LapCatalogController {

    private final ApplicationRuntimePort runtime;

    public LapCatalogController(ApplicationRuntimePort runtime) {
        this.runtime = runtime;
    }

    /**
     * 能力 → 应用 → 动作, 一次取全。
     *
     * <p>逐层短路: 没有能力就不问应用, 没有应用就不问动作 —— 一个连不上聊天平台的
     * 部署上, 这会少打 1+N 次必然超时的请求(每次 5s), 页面从"转 20 秒后空白"变成
     * "立刻说它没有"。
     */
    @GetMapping("/catalog")
    public List<CapabilityEntry> catalog() {
        List<CapabilityView> capabilities = safe(runtime::capabilities);
        List<CapabilityEntry> out = new ArrayList<>(capabilities.size());
        for (CapabilityView c : capabilities) {
            List<ApplicationView> apps = safe(() -> runtime.applicationsFor(c.capabilityId()));
            List<ApplicationEntry> entries = new ArrayList<>(apps.size());
            for (ApplicationView a : apps) {
                entries.add(new ApplicationEntry(
                        a.applicationId(), a.version(), a.name(), a.description(),
                        a.category(), a.capabilities(),
                        safe(() -> runtime.actionsOf(a.applicationId()))));
            }
            out.add(new CapabilityEntry(
                    c.capabilityId(), c.title(), c.description(), c.category(), entries));
        }
        return out;
    }

    /**
     * 读端口**不该**抛 —— 它的契约是读缺席返回空。但"不该"与"不会"是两回事: 一次
     * 未检查的异常会让整页 500, 而这一页坏掉不该影响别人。所以这里兜住并记日志:
     * 页面降级成"没有能力", 日志里留着真正的原因。
     */
    private <T> List<T> safe(java.util.function.Supplier<List<T>> call) {
        try {
            List<T> r = call.get();
            return r == null ? List.of() : r;
        } catch (RuntimeException e) {
            log.warn("LAP 目录读取失败, 该层降级为空: {}", e.toString());
            return List.of();
        }
    }

    /** 一个能力, 以及实现它的应用。 */
    public record CapabilityEntry(
            String capabilityId,
            String title,
            String description,
            String category,
            List<ApplicationEntry> applications) {}

    /** 一个应用, 以及它能做的动作 —— 动作带着 {@code agentHint}(作者写的策略建议)。 */
    public record ApplicationEntry(
            String applicationId,
            String version,
            String name,
            String description,
            String category,
            List<String> capabilities,
            List<ActionSpec> actions) {}
}
