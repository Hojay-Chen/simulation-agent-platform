package com.luxera.companion.runtime.agent.brain;

import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.ResponseBudget;
import com.luxera.companion.interaction.ResponseCommitment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 被吵醒场景: BrainAgent 全路径应把 IGNORE/READ_NO_REPLY 修正为 SHORT_ACK */
@ActiveProfiles("test")
@SpringBootTest
class BrainWokenUpTest {

    @Autowired
    BrainAgent brainAgent;

    private BrainContext wokenCtx() {
        InteractionDecision baseline = new InteractionDecision(InteractionAction.REPLY_NOW,
                ResponseCommitment.CASUAL, 1500, true, false, false, "基准", 0.7,
                ResponseBudget.forCommitment(ResponseCommitment.CASUAL, true));
        return new BrainContext(
                "c1", "u1", "m1", "在吗?出急事了", List.of("用户: 在吗?出急事了"),
                "刚被消息吵醒,还没完全清醒", "DISTRACTED", 0.5, 0.4, 0.5, 0.1, 0.1, 0.1, 0.1, 0.1,
                "平静", 0.3, 0.4, false, "vibrate",
                "new", 0.3, null, "anxious", null, false, false, baseline);
    }

    @Test
    void wokenUpNeverIgnores() {
        BrainDecision d = brainAgent.execute(wokenCtx());
        assertNotNull(d);
        assertNotEquals(BrainDecision.IGNORE, d.action(), "被吵醒不应忽略: " + d.action());
        assertNotEquals(BrainDecision.READ_NO_REPLY, d.action(), "被吵醒不应读了不回: " + d.action());
    }
}
