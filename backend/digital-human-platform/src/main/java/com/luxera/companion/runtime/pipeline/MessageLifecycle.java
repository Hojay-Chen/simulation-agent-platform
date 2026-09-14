package com.luxera.companion.runtime.pipeline;

/**
 * 消息生命周期状态(§11): 这些不是同一个状态。
 * SENT → DELIVERED → NOTIFIED → NOTICED → CHECKED → READ → (RESPONDED | DEFERRED | IGNORED)
 */
public final class MessageLifecycle {

    /** 已存储 */
    public static final String SENT = "SENT";
    /** 已送达(通知可达) */
    public static final String DELIVERED = "DELIVERED";
    /** 通知出现(手机响了/屏幕亮了) */
    public static final String NOTIFIED = "NOTIFIED";
    /** 她注意到通知 */
    public static final String NOTICED = "NOTICED";
    /** 她打开了手机/应用 */
    public static final String CHECKED = "CHECKED";
    /** 她读到了内容 */
    public static final String READ = "READ";
    /** 已回复 */
    public static final String RESPONDED = "RESPONDED";
    /** 已读但决定稍后回(有复查点) */
    public static final String DEFERRED = "DEFERRED";
    /** 决定不回(未读忽略或读后放下) */
    public static final String IGNORED = "IGNORED";

    private MessageLifecycle() {
    }
}
