package com.luxera.companion.agent;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.usermodel.UserModelService;

import java.time.LocalDateTime;
import java.util.List;

/** Agent 运行时所需的全部上下文(数据库是源,这里是投影) */
public class AgentContext {
    public final Companion companion;
    public final Persona persona;
    public final AgentState state;
    public final Relationship relationship;
    public final List<Memory> memories;
    public final UserModelService.UserModelSummary userModel;
    public final List<MessageView> recentMessages;
    public final WorkingMemory.WorkingContext workingMemory;
    /** 本轮调用的工具结果(如刚创建的提醒),供 Prompt 注入 */
    public final String toolResult;
    /** 她此刻在做什么(日常时间表) */
    public final String scheduleDesc;
    public final LocalDateTime now;

    public AgentContext(Companion companion, Persona persona, AgentState state, Relationship relationship,
                        List<Memory> memories, UserModelService.UserModelSummary userModel,
                        List<MessageView> recentMessages, WorkingMemory.WorkingContext workingMemory,
                        String toolResult, String scheduleDesc, LocalDateTime now) {
        this.companion = companion;
        this.persona = persona;
        this.state = state;
        this.relationship = relationship;
        this.memories = memories;
        this.userModel = userModel;
        this.recentMessages = recentMessages;
        this.workingMemory = workingMemory;
        this.toolResult = toolResult;
        this.scheduleDesc = scheduleDesc;
        this.now = now;
    }
}
