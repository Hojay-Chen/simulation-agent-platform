import { Panel } from '@/components/ui'

/**
 * 接口文档 —— 给"拿着钥匙站在门外的那个人"看的。
 *
 * <h2>为什么写在这一页, 而不是丢一个 Swagger 链接</h2>
 *
 * 8092 确实有 OpenAPI 描述(`/v3/api-docs`), 但那是**机器**读的: 它列得出字段, 说不清
 * "哪把钥匙开哪张门""ownerUserId 为什么会被 403"。第三方接入时卡住的地方从来不是
 * 字段名, 是这几句话。所以这一页只写那几句话 + 三个能直接粘的 curl。
 *
 * <h2>它只写对外承诺的那一张面</h2>
 *
 * 控制台自己还用了内省面(JWT)与登录面, 那两张**不是**对外承诺 —— 它们随时可能为了
 * 界面需要而变。写进来会让第三方以为可以依赖它们。
 */
export function Docs() {
  return (
    <div className="space-y-5">
      <Panel title="基址与两张钥匙面">
        <p className="text-sm leading-relaxed text-ink-soft">
          开放面只有一张:<span className="mx-1 font-mono text-ink">/api/v1/openapi/**</span>
          (server:8092)。它有两把钥匙, 分别对应下面两栏 ——
          <strong className="text-ink">它们互不相通</strong>: 拿管理钥去打客户端面, 或反过来,
          都是 401。
        </p>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <FaceCard
            title="管理面"
            header="X-Admin-Key: <管理密钥>"
            who="平台运维"
            what="发放、列出、吊销 API 客户端; 开关某个客户端的代建权限。"
            note="服务端未配置 OPENAPI_ADMIN_KEY 时, 这一面整体回 503(不是 401)。"
          />
          <FaceCard
            title="客户端面"
            header="Authorization: Bearer sap_…"
            who="任何一个程序"
            what="建 / 列 / 读 / 改人格 / 软删 agent, 读实时状态。"
            note="明文钥匙只在创建响应里出现一次 —— 库里只有 sha256, 丢了只能吊销重建。"
          />
        </div>
      </Panel>

      <Panel title="端点">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead>
              <tr className="border-b border-line text-xs uppercase tracking-wider text-ink-faint">
                <th className="py-2 pr-4 font-normal">方法</th>
                <th className="py-2 pr-4 font-normal">路径</th>
                <th className="py-2 pr-4 font-normal">面</th>
                <th className="py-2 font-normal">说明</th>
              </tr>
            </thead>
            <tbody className="text-ink-soft">
              {ENDPOINTS.map((e) => (
                <tr key={`${e.method} ${e.path}`} className="border-b border-line/60">
                  <td className="py-2 pr-4 font-mono text-xs text-accent">{e.method}</td>
                  <td className="py-2 pr-4 font-mono text-xs">{e.path}</td>
                  <td className="py-2 pr-4 text-xs">{e.face}</td>
                  <td className="py-2 text-xs leading-relaxed">{e.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-4 text-xs leading-relaxed text-ink-faint">
          <span className="font-mono text-ink-soft">GET /api/health</span> 不需要任何钥匙 ——
          探活不该依赖钥匙是否有效, 否则"服务活着吗"会变成"我的钥匙还有效吗"。
        </p>
      </Panel>

      <Panel title="三个能直接粘的例子">
        <Step
          n={1}
          title="用管理钥发一个客户端"
          body={`curl -s -X POST https://being.luxera.top/api/v1/openapi/clients \\
  -H 'X-Admin-Key: <管理密钥>' -H 'Content-Type: application/json' \\
  -d '{"name":"my-agent-producer"}'`}
          after="响应里的 apiKey 就是 sap_… 明文, 只在这里出现一次。"
        />
        <Step
          n={2}
          title="用客户端钥建一个 agent"
          body={`curl -s -X POST https://being.luxera.top/api/v1/openapi/agents \\
  -H 'Authorization: Bearer sap_…' -H 'Content-Type: application/json' \\
  -d '{"description":"一位喜欢在下午读书的安静的人。","relationshipType":"friend"}'`}
          after="description 交给平台的人格编译链; 也可以直接传 persona 对象跳过编译。"
        />
        <Step
          n={3}
          title="读它的实时状态"
          body={`curl -s https://being.luxera.top/api/v1/openapi/agents/<agentId>/state \\
  -H 'Authorization: Bearer sap_…'`}
          after="状态由认知链持续写入。**它收到第一条消息之前是空的** —— 空状态不是错误。"
        />
      </Panel>

      <Panel title="代建: 把 agent 放进某个真人的名下">
        <p className="text-sm leading-relaxed text-ink-soft">
          默认情况下, 一个客户端建出来的 agent 归**它自己**(`user_id = clientId`)—— 这是给
          纯程序场景的。若要让 agent 出现在某个真人名下(比如聊天平台的「一键创建 agent 好友」),
          创建时带上
          <span className="mx-1 font-mono text-ink">ownerUserId</span>
          与
          <span className="mx-1 font-mono text-ink">chatAccountId</span>,
          <strong className="text-ink">但只有被标记为可信的客户端才能这么做</strong>。
        </p>
        <pre className="mt-3 overflow-x-auto rounded-lg border border-line bg-sunken/60 p-3 font-mono text-xs leading-relaxed text-ink-soft">
{`curl -s -X PUT https://being.luxera.top/api/v1/openapi/clients/<clientId>/can-act-for-users \\
  -H 'X-Admin-Key: <管理密钥>' -H 'Content-Type: application/json' \\
  -d '{"canActForUsers":true}'`}
        </pre>
        <p className="mt-3 text-xs leading-relaxed text-ink-faint">
          没有这道闸, 任何持 <span className="font-mono">sap_</span> 钥匙的程序都能往任意真人的
          通讯录里塞一个归他所有、他却删不掉的 agent。所以它默认关闭、且是一个**可收回**的开关
          (收回不必换钥, 也不影响已经代建出来的 agent —— 那些 agent 属于真人, 归属不由这个开关决定)。
          <br />
          未标记可信却传了 <span className="font-mono">ownerUserId</span> 的请求, 得到的是
          <span className="mx-1 font-mono">403</span>而不是静默忽略。
        </p>
      </Panel>

      <Panel title="边界: 开放面不做什么">
        <ul className="list-disc space-y-2 pl-5 text-sm leading-relaxed text-ink-soft">
          <li>
            <strong className="text-ink">不提供聊天能力。</strong>
            发消息、读会话、加好友是**聊天平台**的开放面, 不在这里。两个平台各自开放各自的,
            互相之间只通过接口往来 —— 这一条是两个平台"完全独立"最直接的体现。
          </li>
          <li>
            <strong className="text-ink">不暴露认知链的内部概念。</strong>
            感知更新、边界记录、清理某个 peer 的全部历史 —— 那些是认知链自己的动作,
            公开面是内部面的子集, 不是镜像。
          </li>
          <li>
            <strong className="text-ink">删除是软删。</strong>
            <span className="mx-1 font-mono">DELETE</span>落的是
            <span className="mx-1 font-mono">deleted_at</span> —— 认知链不再推进它, 但记忆与
            关系仍在库里。对"删掉就查无此人"有硬要求的话, 现在还没有这条路。
          </li>
        </ul>
      </Panel>
    </div>
  )
}

function FaceCard({ title, header, who, what, note }: {
  title: string
  header: string
  who: string
  what: string
  note: string
}) {
  return (
    <div className="rounded-lg border border-line bg-sunken/40 p-3">
      <p className="text-sm text-ink">{title}<span className="ml-2 text-xs text-ink-faint">{who}</span></p>
      <code className="mt-2 block overflow-x-auto rounded border border-line bg-raised px-2 py-1 font-mono text-xs text-accent">
        {header}
      </code>
      <p className="mt-2 text-xs leading-relaxed text-ink-soft">{what}</p>
      <p className="mt-1.5 text-xs leading-relaxed text-ink-faint">{note}</p>
    </div>
  )
}

function Step({ n, title, body, after }: {
  n: number
  title: string
  body: string
  after: string
}) {
  return (
    <div className="mb-5 last:mb-0">
      <p className="mb-2 flex items-center gap-2 text-sm text-ink">
        <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-accent-soft font-mono text-xs text-accent">
          {n}
        </span>
        {title}
      </p>
      <pre className="overflow-x-auto rounded-lg border border-line bg-sunken/60 p-3 font-mono text-xs leading-relaxed text-ink-soft">
        {body}
      </pre>
      <p className="mt-2 text-xs leading-relaxed text-ink-faint">{after}</p>
    </div>
  )
}

const ENDPOINTS = [
  { method: 'POST', path: '/api/v1/openapi/clients', face: '管理', note: '发一个客户端, 返回明文 sap_ 钥匙(仅此一次)' },
  { method: 'GET', path: '/api/v1/openapi/clients', face: '管理', note: '列出客户端 —— 响应里不含 apiKey' },
  { method: 'DELETE', path: '/api/v1/openapi/clients/{clientId}', face: '管理', note: '吊销客户端, 其名下 agent 保留' },
  { method: 'PUT', path: '/api/v1/openapi/clients/{clientId}/can-act-for-users', face: '管理', note: '开关代建权限' },
  { method: 'POST', path: '/api/v1/openapi/agents', face: '客户端', note: '建 agent: description 走人格编译, 或直接传 persona' },
  { method: 'GET', path: '/api/v1/openapi/agents', face: '客户端', note: '列出本客户端可见的 agent' },
  { method: 'GET', path: '/api/v1/openapi/agents/{agentId}', face: '客户端', note: '单个 agent(含当前人格)' },
  { method: 'PUT', path: '/api/v1/openapi/agents/{agentId}/persona', face: '客户端', note: '重编译人格, 落一个新版本' },
  { method: 'DELETE', path: '/api/v1/openapi/agents/{agentId}', face: '客户端', note: '软删' },
  { method: 'GET', path: '/api/v1/openapi/agents/{agentId}/state', face: '客户端', note: '实时状态(情绪/亲密度/困倦度)' },
  { method: 'GET', path: '/api/health', face: '无', note: '探活, 不需要钥匙' },
] as const
