#!/usr/bin/env bash
# G4 — 对外 OpenAPI 服务(openapi:8092)验收: 三方创建/管理/使用自己的仿真 agent。
#
# 断言链:
#   O1 环境就绪: PG + openapi jar 可用
#   O2 服务起: 8092 /api/health 200 + springdocs spec 可读
#   O3 鉴权矩阵: 管理面无钥 401 / 客户端面无钥 401 / 伪造钥 401
#   O4 管理面建客户端: 对管理钥 → 201 + 明文 key 只出现一次
#   O5 客户端 CRUD: 用 key 建 agent(编译链)→ 列 → 读详情 → 改 persona → 删
#   O6 归属隔离: A 的 key 读不到 B 的 agent(404)
#   O7 吊销: revoke 后同一把 key 立即 401
#
# 用法: bash scripts/check-openapi.sh   (自动起停 openapi 服务)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${BASE:-http://127.0.0.1:8092}"
ADMIN="${OPENAPI_ADMIN_KEY:-check-openapi-admin-key}"
JAR="${OPENAPI_JAR:-$ROOT/openapi/build/libs/simulation-agent-openapi-1.0.0.jar}"
TMP="$(mktemp -d /tmp/check-openapi.XXXXXX)"
PIDS=()
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }
# 递归杀整棵树 —— 见 check-console.sh 里的同款注释: 只杀直接子进程会留下
# 孤儿 java 占着 8092, 下一个验收脚本复用到它时会撞上另一把管理钥。
kill_tree() {
  local pid="$1" child
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill_tree "$p"; done; }
trap cleanup EXIT

psqlc() { PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc "$@"; }

echo ""
echo "══════════ G4 对外 OpenAPI 验收 (check-openapi) ══════════"

# ── O1 环境就绪 ──
note "O1: 环境就绪"
[ -f "$JAR" ] && ok "$(basename "$JAR") 存在" || { fail "缺 $JAR —— 先 gradle :openapi:bootJar"; echo ""; echo "❌ 验收未通过"; exit 1; }
psqlc "select 1" >/dev/null 2>&1 && ok "PG 在" || { fail "PG 不可达(companion 库)"; }

# ── O2 服务起 ──
note "O2: 服务起"
if curl -s -m 2 -o /dev/null "$BASE/api/health"; then
  # 复用一个"已在跑"的实例前先验自己这把管理钥 —— 否则 O4 会以
  # "invalid or missing api key" 收场, 让人以为是脚本 bug, 其实是撞了
  # 另一个验收脚本(check-console.sh)留下的实例的钥匙。503 = 那个实例压根
  # 没配管理钥。
  PROBE=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H "X-Admin-Key: $ADMIN" "$BASE/api/v1/openapi/clients")
  if [ "$PROBE" = "200" ]; then
    ok "openapi 已在跑 (复用, 管理钥对得上)"
  else
    fail "8092 已在跑, 但它不认本脚本的管理钥 (期望 200, 实得 $PROBE)"
    # 复用不了时**先分清占着 8092 的是谁** —— systemd 的 luxera-agent-openapi
    # 就是生产服务。原本这里无差别地建议 pkill, 照着做等于打掉线上, 而 systemd
    # 随后会按 Restart 策略拉起来 —— 故障看起来像"服务自己重启了"。
    if systemctl is-active --quiet luxera-agent-openapi 2>/dev/null; then
      echo "   占着 8092 的是**生产服务**(systemd: luxera-agent-openapi) —— 不要 pkill 它。"
      echo "   把它的管理钥交给本脚本即可复用(生产钥在 /etc/agent-platform/.env):"
      echo "     sudo bash -c 'set -a; . /etc/agent-platform/.env; set +a; exec sudo -u ubuntu \\"
      echo "       env OPENAPI_ADMIN_KEY=\"\$OPENAPI_ADMIN_KEY\" bash scripts/check-openapi.sh'"
    else
      echo "   多半是 check-console.sh 留下的实例。停掉再跑: pkill -f simulation-agent-openapi"
    fi
    echo ""; echo "❌ 验收未通过"; exit 1
  fi
else
  # exec 让子 shell 变成 java —— 否则 $! 是子 shell 的 pid, cleanup 杀不到 java
  ( cd "$ROOT" && exec env OPENAPI_ADMIN_KEY="$ADMIN" java -jar "$JAR" ) \
      > "$TMP/openapi.log" 2>&1 &
  PIDS+=("$!")
  for _ in $(seq 1 40); do curl -s -m 2 -o /dev/null "$BASE/api/health" && break || sleep 2; done
  curl -s -m 3 -o /dev/null "$BASE/api/health" \
    && ok "openapi 起来 (pid=$!)" \
    || { fail "8092 没起来: $(tail -3 "$TMP/openapi.log")"; }
fi
# springdoc spec 可读(对外文档面公开)
SPEC_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")
[ "$SPEC_CODE" = "200" ] && ok "OpenAPI 3.1 spec 可读 (/v3/api-docs 200)" || fail "spec 期望 200, 实得 $SPEC_CODE"

# ── O3 鉴权矩阵 ──
note "O3: 鉴权矩阵"
C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/openapi/clients" -H 'Content-Type: application/json' -d '{"name":"x"}')
[ "$C" = "401" ] && ok "管理面无 X-Admin-Key → 401" || fail "管理面无钥期望 401, 实得 $C"
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/openapi/agents")
[ "$C" = "401" ] && ok "客户端面无 Bearer → 401" || fail "客户端面无钥期望 401, 实得 $C"
C=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer sap_forgeddeadbeef" "$BASE/api/v1/openapi/agents")
[ "$C" = "401" ] && ok "客户端面伪造钥 → 401" || fail "伪造钥期望 401, 实得 $C"

# ── O4 管理面建客户端 ──
note "O4: 管理面建客户端 (明文 key 仅此一次)"
CLIENT_RESP=$(curl -s -X POST "$BASE/api/v1/openapi/clients" -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN" -d '{"name":"check-openapi-'$(date +%s)'"}')
KEY=$(echo "$CLIENT_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["apiKey"])')
CLIENT_ID=$(echo "$CLIENT_RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["clientId"])')
[ -n "$KEY" ] && [ "${KEY:0:4}" = "sap_" ] && ok "建客户端: ${KEY:0:12}... (clientId=$CLIENT_ID)" || { fail "建客户端失败: $CLIENT_RESP"; echo ""; echo "❌ 验收未通过"; exit 1; }

# ── O5 客户端 CRUD ──
note "O5: 客户端 CRUD 闭环"
CREATE=$(curl -s -X POST "$BASE/api/v1/openapi/agents" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d '{"description":"一个爱读书的安静 agent","relationshipType":"friend"}')
AGENT_ID=$(echo "$CREATE" | python3 -c 'import sys,json;print(json.load(sys.stdin)["agentId"])')
[ -n "$AGENT_ID" ] && ok "建 agent: $AGENT_ID ($(echo "$CREATE" | python3 -c 'import sys,json;print(json.load(sys.stdin)["name"])'))" || { fail "建 agent 失败: $CREATE"; }
LIST_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" "$BASE/api/v1/openapi/agents")
[ "$LIST_CODE" = "200" ] && ok "列 agent 200" || fail "列 agent 期望 200, 实得 $LIST_CODE"
GET_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" "$BASE/api/v1/openapi/agents/$AGENT_ID")
[ "$GET_CODE" = "200" ] && ok "读详情 200" || fail "读详情期望 200, 实得 $GET_CODE"
UPD_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$BASE/api/v1/openapi/agents/$AGENT_ID/persona" -d '{"description":"变得更沉稳了","reason":"G4 验收更新"}')
[ "$UPD_CODE" = "200" ] && ok "改 persona 200" || fail "改 persona 期望 200, 实得 $UPD_CODE"
STATE_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" "$BASE/api/v1/openapi/agents/$AGENT_ID/state")
[ "$STATE_CODE" = "200" ] && ok "读状态 200 (server 认知链未起也回 200 — 纯数据面)" || fail "读状态期望 200, 实得 $STATE_CODE"
DEL_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $KEY" "$BASE/api/v1/openapi/agents/$AGENT_ID")
[ "$DEL_CODE" = "204" ] && ok "删 agent 204" || fail "删 agent 期望 204, 实得 $DEL_CODE"

# ── O6 归属隔离 ──
note "O6: 归属隔离 (A 的 key 看不到 B 的 agent)"
AGENT2=$(curl -s -X POST "$BASE/api/v1/openapi/agents" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d '{"description":"归属测试"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["agentId"])')
# 第二个客户端
CLIENT2=$(curl -s -X POST "$BASE/api/v1/openapi/clients" -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN" -d '{"name":"check-openapi-b-'$(date +%s)'"}')
KEY2=$(echo "$CLIENT2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["apiKey"])')
ISO_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY2" "$BASE/api/v1/openapi/agents/$AGENT2")
[ "$ISO_CODE" = "404" ] && ok "B 用自己的 key 读 A 的 agent → 404 (归属隔离)" || fail "归属隔离期望 404, 实得 $ISO_CODE"

# 断言完就立刻删掉 AGENT2 —— **必须在这里, 不能挪到最后的收尾段**: 下一步 O7 会吊销
# $KEY, 那之后再用它删 agent 就是 401(这条实测过: 把删除放到 O7 之后, 收尾本身
# 报的就是 401, 于是"修好的收尾"反过来成了新的漏点)。
CL2=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $KEY" "$BASE/api/v1/openapi/agents/$AGENT2")
[ "$CL2" = "204" ] && ok "删掉归属测试 agent (O6 造的)" \
  || fail "归属测试 agent 没删掉 (期望 204, 实得 $CL2) —— 它会作为'晚晚'留在生产库的伴侣列表里"

# ── O7 吊销 + 收尾 ──
note "O7: 吊销客户端"
curl -s -X DELETE -H "X-Admin-Key: $ADMIN" -o /dev/null "$BASE/api/v1/openapi/clients/$CLIENT_ID"
REV_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" "$BASE/api/v1/openapi/agents")
[ "$REV_CODE" = "401" ] && ok "吊销后同一把 key 立即 401" || fail "吊销后期望 401, 实得 $REV_CODE"

# ── O8 收尾: 吊销 O6 造的那把钥匙 ──
#
# 本脚本跑的是**生产库**(companion, 不是 companion_test), 所以验收留下的每一样
# 东西都是别人能看见的: agent 会作为"晚晚"出现在聊天平台的伴侣列表里, 而一把没吊销
# 的钥匙就是一把没人再持有、也没人再记得的有效凭据。
#
# 这里曾经漏掉过 O6 造的两样东西(AGENT2 与 CLIENT2), 收尾只吊销了 CLIENT_ID。
# 后果是**每跑一次就留下一个活 agent 与一把 ACTIVE 的钥匙** —— 到 2026-09-19
# 已累积 8 把 ACTIVE 的 check-openapi-b-*。
# AGENT2 的删除在 O6 末尾(那时 $KEY 还有效); 这里只剩 CLIENT2 的吊销,
# 它走管理钥, 所以放在 O7 之后没有顺序问题。
note "O8: 收尾(吊销 O6 造的客户端)"
# 注意 CLIENT2 是**响应体**, 不是 id —— 要像上面 CLIENT_ID 那样解析出 clientId。
# (直接把 $CLIENT2 拼进 URL 会得到一个含 JSON 的路径, curl 当场报错, 而
#  `set -euo pipefail` 让脚本在这里静默退出: 收尾段连同最后的 ✅/❌ 一起不打印,
#  看起来像"跑完了", 实际是漏在了最后一步。)
if [ -n "${CLIENT2:-}" ]; then
  CLIENT2_ID=$(echo "$CLIENT2" | python3 -c 'import sys,json;print(json.load(sys.stdin)["clientId"])' 2>/dev/null || true)
  curl -s -X DELETE -H "X-Admin-Key: $ADMIN" -o /dev/null "$BASE/api/v1/openapi/clients/$CLIENT2_ID"
  REV2=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY2" "$BASE/api/v1/openapi/agents")
  [ "$REV2" = "401" ] && ok "吊销第二个客户端(O6 造的)" \
    || fail "第二个客户端没吊销掉 (期望 401, 实得 $REV2) —— 它会作为 ACTIVE 留在库里"
fi

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 对外 OpenAPI 验收通过 (G4 — 三方创建/管理/使用自己的仿真 agent)"
else
  echo "❌ 对外 OpenAPI 验收未通过"
  echo "   日志: $TMP"
  exit 1
fi
