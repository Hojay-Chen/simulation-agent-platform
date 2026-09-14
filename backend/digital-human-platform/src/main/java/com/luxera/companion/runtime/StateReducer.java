package com.luxera.companion.runtime;

/**
 * 状态归约器(§61/§99): 所有状态变更必须经过 Reducer, 保证状态变化有来源、可追踪。
 * Agent/LLM 只产出 delta, 不直接 UPDATE 状态。
 *
 * @param <E> 事件/增量类型
 * @param <S> 状态类型
 */
public interface StateReducer<E, S> {

    S apply(S state, E event);
}
