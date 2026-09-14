package com.luxera.companion.agent;

import com.luxera.companion.behavior.BehaviorDecision;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 §7 Cache-aware Context Compiler: 分层编译 + 稳定前缀 hash。
 * L0(稳定前缀)在内容不变时 hash 必须稳定 —— 相同 agent 的连续调用可复用 provider prefix cache。
 */
@ActiveProfiles("test")
@SpringBootTest
class ContextLayerTest {

    @Autowired
    ContextCompiler compiler;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("ctx-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
    }

    private CompanionContext dummyCtx() {
        Companion companion = companionRepository.findById(companionId).orElseThrow();
        return new CompanionContext(
                companion, null, null, null, null, List.of(), List.of(), List.of(), null, null, null,
                List.of(), null, List.of(), List.of(), null, null, "现在是周一 20:00,小满在悠闲地休息。",
                null, java.time.LocalDateTime.now(), null, null, List.of(), null);
    }

    @Test
    void stablePrefixHashIsStableAcrossCompilations() {
        // 同 agent 相同静态内容 → L0 hash 稳定(prefix cache 前提)
        CompanionContext ctx = dummyCtx();
        CompiledContext a = compiler.compile(ctx, BehaviorDecision.respond(), null, null);
        CompiledContext b = compiler.compile(ctx, BehaviorDecision.respond(), null, null);
        assertEquals(a.stableHash(), b.stableHash(), "L0 稳定前缀 hash 必须稳定");
        assertFalse(a.stableHash().isBlank());
    }

    @Test
    void layeredOrderStableBeforeDynamic() {
        CompanionContext ctx = dummyCtx();
        CompiledContext c = compiler.compile(ctx, BehaviorDecision.respond(), null, "语气 轻松");
        String full = c.fullText();
        // 稳定内容(L0 你是谁)在动态内容(现在/行为意图)之前
        int identity = full.indexOf("【你是谁】");
        int now = full.indexOf("【现在】");
        int intent = full.indexOf("【本回合的行为意图】");
        assertTrue(identity >= 0 && identity < now, "L0 身份应在 L2 动态之前");
        assertTrue(now >= 0 && now < intent, "L2 动态应在 L3 行为意图之前");
        assertTrue(c.l3().contains("语气 轻松"), "表达提示在 L3");
    }
}
