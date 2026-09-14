package com.luxera.companion.digitalhuman.conversation;

/**
 * V10 §15.2 ChatMessageDraft: Conversation Runtime 产出的消息草稿。
 * 除文本外可携带元信息(发送顺序/语气), 供验证器与发送器使用。
 */
public record ChatMessageDraft(int index, String text, String toneHint) {

    public ChatMessageDraft(int index, String text) {
        this(index, text, null);
    }

    public static ChatMessageDraft of(int index, String text) {
        return new ChatMessageDraft(index, text);
    }
}
