package com.luxera.companion.runtime;

/**
 * Agent 契约(§58/§96): 每个认知 Agent 实现此接口。
 * 输入/输出都是结构化类型, 不允许 Agent 返回自由自然语言。
 */
public interface Agent<I, O> {

    O execute(I context);
}
