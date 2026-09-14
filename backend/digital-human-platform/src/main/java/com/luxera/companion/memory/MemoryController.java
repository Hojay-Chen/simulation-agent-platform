package com.luxera.companion.memory;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.persona.CompanionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/companions/{companionId}/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final MemoryAssociationService associationService;
    private final MemoryEntityService entityService;
    private final ChatWorldPort chatWorld;
    private final CompanionService companionService;
    private final CurrentUser currentUser;

    public MemoryController(MemoryService memoryService, MemoryAssociationService associationService,
                            MemoryEntityService entityService,
                            ChatWorldPort chatWorld, CompanionService companionService,
                            CurrentUser currentUser) {
        this.memoryService = memoryService;
        this.associationService = associationService;
        this.entityService = entityService;
        this.chatWorld = chatWorld;
        this.companionService = companionService;
        this.currentUser = currentUser;
    }

    /** P2: 用户常提的实体(长期指代: "那家公司/上次那个地方") */
    @GetMapping("/entities")
    public List<MemoryEntity> entities(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return entityService.recent(userId, companionId, 20);
    }

    @GetMapping
    public List<Memory> list(@PathVariable String companionId,
                             @RequestParam(required = false) String type) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return memoryService.list(userId, companionId, type);
    }

    /** 记忆透明: 搜索记忆并按相关度返回(附发生时间/来源) */
    @GetMapping("/search")
    public List<Memory> search(@PathVariable String companionId, @RequestParam String q) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return memoryService.retrieve(userId, companionId, q, 20);
    }

    @GetMapping("/export")
    public Map<String, Object> export(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return Map.of(
                "companionId", companionId,
                "count", memoryService.count(userId, companionId),
                "memories", memoryService.list(userId, companionId, null)
        );
    }

    /** 记忆图谱(设计文档 28/98 节) */
    @GetMapping("/graph")
    public Map<String, Object> graph(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        return associationService.graph(userId, companionId, 60);
    }

    /** 记忆透明: 为什么你知道(设计文档 83 节) — 返回命中的记忆及其来源对话摘录 */
    @GetMapping("/why")
    public List<Map<String, Object>> why(@PathVariable String companionId, @RequestParam String q) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        List<Memory> hits = memoryService.retrieve(userId, companionId, q, 10);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Memory m : hits) {
            result.add(Map.of("memory", m, "source", sourceExcerpt(m)));
        }
        return result;
    }

    /** 单条记忆的来源对话摘录 */
    @GetMapping("/{memoryId}/source")
    public Map<String, Object> source(@PathVariable String companionId, @PathVariable String memoryId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        Memory m = memoryService.list(userId, companionId, null).stream()
                .filter(x -> x.getId().equals(memoryId))
                .findFirst()
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("记忆不存在"));
        return Map.of("memory", m, "source", sourceExcerpt(m));
    }

    private List<Map<String, Object>> sourceExcerpt(Memory m) {
        if (m.getSourceType() == null || !"conversation".equals(m.getSourceType()) || m.getSourceId() == null) {
            return List.of();
        }
        List<MessageView> msgs = chatWorld.messages(m.getSourceId());
        if (msgs.isEmpty()) return List.of();
        LocalDateTime anchor = m.getOccurredAt() != null ? m.getOccurredAt() : m.getCreatedAt();
        int idx = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < msgs.size(); i++) {
            long diff = Math.abs(Duration.between(msgs.get(i).getCreatedAt(), anchor).toMillis());
            if (diff < best) {
                best = diff;
                idx = i;
            }
        }
        int from = Math.max(0, idx - 2);
        int to = Math.min(msgs.size(), idx + 3);
        return msgs.subList(from, to).stream()
                .map(x -> Map.<String, Object>of(
                        "sender", x.getSenderType(),
                        "content", x.getContent(),
                        "createdAt", x.getCreatedAt() != null ? x.getCreatedAt().toString() : ""))
                .toList();
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String companionId, @PathVariable String memoryId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        memoryService.forget(userId, companionId, memoryId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAll(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        companionService.requireOwned(userId, companionId);
        memoryService.clearAll(userId, companionId);
    }
}
