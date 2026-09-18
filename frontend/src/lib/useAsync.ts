import { useCallback, useEffect, useState } from 'react'

/** 异步取数的最小骨架 —— 三个页面都在做同一件事, 不值得各写一遍。 */
export interface AsyncState<T> {
  data: T | null
  loading: boolean
  error: string | null
  reload: () => void
  setData: (next: T | null) => void
}

export function useAsync<T>(fn: () => Promise<T>, deps: readonly unknown[]): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  // fn 每次渲染都是新引用, 不能进依赖数组 —— 依赖由调用方通过 deps 显式声明,
  // 这是这里唯一诚实的做法(把 fn 塞进 deps 会变成无限循环)。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    run()
      .then((r) => {
        if (alive) setData(r)
      })
      .catch((e: unknown) => {
        if (alive) setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [run, nonce])

  const reload = useCallback(() => setNonce((n) => n + 1), [])

  return { data, loading, error, reload, setData }
}

/**
 * 一个会慢慢走的"现在"。
 *
 * <h2>为什么不能直接 `Date.now()`</h2>
 *
 * 因为时间轴上的"现在"线是画在**渲染结果**里的。每次渲染现取一次当前时刻, 会让这条
 * 线在两次渲染之间移动 —— 而两次渲染的间隔是不确定的(悬停、点一下、父组件刷新都会
 * 触发)。一条位置不确定的红线看起来像加载动画, 而不是一个事实。
 *
 * <h2>为什么是 60 秒</h2>
 *
 * 因为这一页在两种状态下都要对。看它的人可能是**站在那儿盯着**(这时 60 秒的粒度
 * 完全够 —— 一条日程的尺度是半小时), 也可能是**开着放一整天**(这时每秒重渲染
 * 一次就是纯浪费, 而且会让屏幕右下角的时间一直闪)。
 *
 * 默认 60 秒是这两者的折中。要更细的粒度就传参数 —— 但别传 1000 毫秒,
 * 那样这条线会开始"跳", 而跳动的数据看起来不可信。
 */
export function useNow(intervalMs = 60_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs)
    // 回到前台时立刻对一次: 笔记本合盖一小时后打开, 那条线还停在一小时前的位置,
    // 而它看起来完全正常 —— 这是这个 hook 唯一一种会骗人的方式。
    const onVisible = () => { if (!document.hidden) setNow(Date.now()) }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(id)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [intervalMs])
  return now
}
