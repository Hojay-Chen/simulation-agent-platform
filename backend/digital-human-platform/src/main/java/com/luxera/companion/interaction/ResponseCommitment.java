package com.luxera.companion.interaction;

/** 消息值得投入的精力(设计文档 §七) */
public enum ResponseCommitment {
    ACK(0),      // 应和
    CASUAL(1),   // 闲聊
    ENGAGED(2),  // 投入
    DEEP(3);     // 深入

    public final int level;

    ResponseCommitment(int level) {
        this.level = level;
    }
}
