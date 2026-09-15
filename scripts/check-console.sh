#!/usr/bin/env bash
# G6 — 仿真 Agent 控制台验收: 前端产物 + vite 代理 + 两面钥匙端到端。
#
# 控制台与仓 1 聊天前端的关键差别: 它只打一个后端(openapi:8092), 分流不在
# "主机"而在"面"(管理钥 / 客户端钥)。所以本脚本除了验通路, 还要验**面错配
# 会被拒** —— 那是 faceOf() 判错的唯一可见症状。
#
#   C1 环境: openapi jar + 控制台依赖 + 构建产物
#   C2 服务起: 8092 /api/health 200
#   C3 vite dev 起 + SPA 可服务(index.html 可达)
#   C4 vite 代理分流: 经 vite 打 /api/v1/openapi/** 落到 8092
#      (无钥应为 401 —— 404/502 说明代理没落到 8092)
#   C5 管理面端到端(经 vite): 建客户端 → 拿明文 key
#   C6 客户端面端到端(经 vite): 建 agent → 列 → 读详情 → 读状态 → 删
#   C7 面错配被拒: 管理钥当 Bearer → 401; 客户端钥当 X-Admin-Key → 401
#
# 用法: bash scripts/check-console.sh   (自动起停 vite; openapi 用已在跑的或自起)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${BASE:-http://127.0.0.1:8092}"
VITE="${VITE:-http://127.0.0.1:5174}"
ADMIN="${OPENAPI_ADMIN_KEY:-check-console-admin-key}"
JAR="${OPENAPI_JAR:-$ROOT/openapi/build/libs/simulation-agent-openapi-1.0.0.jar}"
TMP="$(mktemp -d /tmp/check-console.XXXXXX)"
PIDS=()
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }
# 递归杀整棵树 —— npm run dev 会 fork 出 node/vite, 只 kill npm 的 pid
# 会留下 vite 继续占 5174(下一个脚本复用到它, 拿到的是上个脚本的状态)。
kill_tree() {
  local pid="$1" child
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill_tree "$p"; done; }
trap cleanup EXIT

wait_up() { local url="$1" tries="${2:-30}"; for _ in $(seq 1 "$tries"); do curl -s -m 2 -o /dev/null "$url" && return 0 || true; sleep 2; done; return 1; }
psqlc() { PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc "$@"; }
jget() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))" 2>/dev/null; }

echo ""
echo "══════════ G6 仿真 Agent 控制台验收 (check-console) ══════════"

# ── C1 环境 ──
note "C1: 环境就绪"
[ -f "$JAR" ] && ok "$(basename "$JAR") 存在" || { fail "缺 $JAR —— 先 gradle :openapi:bootJar"; echo ""; echo "❌ 验收未通过"; exit 1; }
psqlc "select 1" >/dev/null 2>&1 && ok "PG 在" || fail "PG 不可达(companion 库)"
[ -d "$ROOT/frontend/node_modules" ] && ok "控制台依赖已装" || { fail "缺 frontend/node_modules —— 先 cd frontend && npm ci"; echo ""; echo "❌ 验收未通过"; exit 1; }
[ -f "$ROOT/frontend/dist/index.html" ] && ok "构建产物在 (frontend/dist)" || { fail "缺 frontend/dist —— 先 npm run build"; echo ""; echo "❌ 验收未通过"; exit 1; }

# ── C2 服务起 ──
note "C2: openapi:8092"
if curl -s -m 2 -o /dev/null "$BASE/api/health"; then
  # "已在跑就复用" 是个陷阱: 复用来的实例用的可能是**另一把** OPENAPI_ADMIN_KEY
  # (比如 check-openapi.sh 刚留下的), 于是 C5 一开始就 401, 报错却是
  # "invalid or missing api key" —— 看着像脚本写错了, 其实是撞了别人的钥匙。
  # 所以复用前先用自己这把探一下管理面, 探不通就说清楚。
  PROBE=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H "X-Admin-Key: $ADMIN" "$BASE/api/v1/openapi/clients")
  if [ "$PROBE" = "200" ]; then
    ok "openapi 已在跑 (复用, 管理钥对得上)"
  elif [ "$PROBE" = "503" ]; then
    fail "8092 已在跑但没配 OPENAPI_ADMIN_KEY(管理面 503) —— 它是别的用途起的实例"
    echo "   停掉它再跑本脚本: pkill -f simulation-agent-openapi"
    echo ""; echo "❌ 验收未通过"; exit 1
  else
    fail "8092 已在跑, 但它不认本脚本的管理钥 (期望 200, 实得 $PROBE)"
    echo "   很可能上一个验收脚本(check-openapi.sh)留下的实例, 用的是另一把 key。"
    echo "   停掉它再跑: pkill -f simulation-agent-openapi"
    echo ""; echo "❌ 验收未通过"; exit 1
  fi
else
  # ⚠️ 必须 exec —— `( cd X && java … & )` 里的 & 绑定的是整个 `cd && java` 列表,
  # $! 拿到的是**子 shell** 的 pid 而不是 java 的。cleanup 杀掉子 shell 后 java
  # 变成孤儿继续占着 8092, 于是下一个验收脚本复用到它、撞上另一把管理钥,
  # 报出来的却是 "invalid or missing api key"(这个坑真踩过)。
  # exec 让子 shell **变成** java, pid 才是对的。
  ( cd "$ROOT" && exec env OPENAPI_ADMIN_KEY="$ADMIN" java -jar "$JAR" ) \
      > "$TMP/openapi.log" 2>&1 &
  PIDS+=("$!")
  wait_up "$BASE/api/health" 40 && ok "openapi 起来" || { fail "8092 没起来: $(tail -3 "$TMP/openapi.log")"; echo ""; echo "❌ 验收未通过"; exit 1; }
fi

# ── C3 vite dev + SPA ──
note "C3: vite dev"
# 同样要 exec: 否则 kill 掉的是子 shell, npm/vite 继续活着占 5174。
# 且 vite 会 fork 出真正的 node 进程, 所以记的 pid 用进程组收尾(见 cleanup)。
( cd "$ROOT/frontend" && exec npm run dev ) > "$TMP/vite.log" 2>&1 &
PIDS+=("$!")
wait_up "$VITE" 40 || { fail "vite dev 没起来: $(tail -3 "$TMP/vite.log")"; echo ""; echo "❌ 验收未通过"; exit 1; }
SPA=$(curl -s -m 5 "$VITE/")
echo "$SPA" | grep -q 'id="root"' && ok "SPA 可服务 (/ 返回挂载点)" || fail "SPA 首页异常: $(echo "$SPA" | head -c 120)"

# ── C4 vite 代理分流 ──
note "C4: vite 代理 /api/v1/openapi/** → 8092"
# 无钥应当是 401 —— 这个断言同时证明"代理确实落到了 8092":
# 代理没配上会 404, 落到错的上游会 404/502, 只有真的到了 openapi 才会 401。
NOAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$VITE/api/v1/openapi/agents")
[ "$NOAUTH" = "401" ] && ok "经 vite 无钥 → 401 (确实落到 8092)" \
  || fail "经 vite 无钥期望 401, 实得 $NOAUTH — 代理没落到 8092"

# ── C5 管理面端到端 ──
note "C5: 管理面建客户端(经 vite, X-Admin-Key)"
CNAME="check-console-$(date +%s)"
CLIENT_RESP=$(curl -s -X POST "$VITE/api/v1/openapi/clients" \
  -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN" \
  -d "{\"name\":\"$CNAME\"}")
KEY=$(echo "$CLIENT_RESP" | jget apiKey)
CLIENT_ID=$(echo "$CLIENT_RESP" | jget clientId)
if [ -n "$KEY" ] && [ "${KEY:0:4}" = "sap_" ]; then
  ok "建客户端: ${KEY:0:12}… (clientId=$CLIENT_ID)"
else
  fail "建客户端失败: $CLIENT_RESP"; echo ""; echo "❌ 验收未通过"; exit 1
fi
# 明文钥匙此后不可再取 —— 列表接口必须不含 apiKey 字段
LIST_BODY=$(curl -s -H "X-Admin-Key: $ADMIN" "$VITE/api/v1/openapi/clients")
echo "$LIST_BODY" | grep -q '"apiKey"' && fail "列表接口泄漏了 apiKey 字段" \
  || ok "列表接口不含 apiKey (明文仅创建时出现一次)"

# ── C6 客户端面端到端 ──
note "C6: 客户端面 CRUD(经 vite, Bearer sap_…)"
CREATE=$(curl -s -X POST "$VITE/api/v1/openapi/agents" \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"description":"控制台验收用的一位安静 agent, 喜欢在下午读书。","relationshipType":"friend"}')
AGENT_ID=$(echo "$CREATE" | jget agentId)
[ -n "$AGENT_ID" ] && ok "建 agent: $AGENT_ID" || { fail "建 agent 失败: $CREATE"; echo ""; echo "❌ 验收未通过"; exit 1; }

LIST_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" "$VITE/api/v1/openapi/agents")
[ "$LIST_CODE" = "200" ] && ok "列表 200" || fail "列表期望 200, 实得 $LIST_CODE"

DETAIL=$(curl -s -H "Authorization: Bearer $KEY" "$VITE/api/v1/openapi/agents/$AGENT_ID")
echo "$DETAIL" | grep -q '"persona"' && ok "详情含 persona (控制台人格面板的数据源)" \
  || fail "详情缺 persona: $(echo "$DETAIL" | head -c 160)"

STATE=$(curl -s -H "Authorization: Bearer $KEY" "$VITE/api/v1/openapi/agents/$AGENT_ID/state")
echo "$STATE" | grep -q "$AGENT_ID" && ok "状态可读 (mood/closeness/sleepiness 的来源)" \
  || fail "状态异常: $(echo "$STATE" | head -c 160)"

UPD=$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  "$VITE/api/v1/openapi/agents/$AGENT_ID/persona" \
  -d '{"description":"变得更沉稳了一些。","reason":"G6 控制台验收"}')
[ "$UPD" = "200" ] && ok "改 persona 200" || fail "改 persona 期望 200, 实得 $UPD"

DEL=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $KEY" "$VITE/api/v1/openapi/agents/$AGENT_ID")
[ "$DEL" = "204" ] && ok "软删 204" || fail "软删期望 204, 实得 $DEL"

# ── C7 面错配被拒(faceOf 判错的唯一可见症状) ──
note "C7: 两面互不相通(带错钥匙必须被拒)"
WRONG1=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ADMIN" "$VITE/api/v1/openapi/agents")
[ "$WRONG1" = "401" ] && ok "管理钥当 Bearer 用 → 401" || fail "管理钥当 Bearer 期望 401, 实得 $WRONG1"
WRONG2=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Admin-Key: $KEY" -X POST \
  -H 'Content-Type: application/json' "$VITE/api/v1/openapi/clients" -d '{"name":"nope"}')
[ "$WRONG2" = "401" ] && ok "客户端钥当 X-Admin-Key 用 → 401" || fail "客户端钥当管理钥期望 401, 实得 $WRONG2"

# 收尾: 吊销本次验收的客户端(不动库里的既有数据)
curl -s -X DELETE -H "X-Admin-Key: $ADMIN" -o /dev/null "$VITE/api/v1/openapi/clients/$CLIENT_ID" || true
ok "已吊销验收客户端 $CLIENT_ID"

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 控制台验收通过 (G6 — 前端产物 + vite 代理 + 两面钥匙端到端)"
else
  echo "❌ 控制台验收未通过"
  echo "   日志: $TMP"
  exit 1
fi
