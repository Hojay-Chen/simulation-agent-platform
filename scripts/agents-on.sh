#!/usr/bin/env bash
# agents-off.sh 的反面 —— 让全部 agent 重新开始推进。
#
# 用法: bash scripts/agents-on.sh
#
# ⚠ 这一条会**立刻恢复花钱**。跑之前先想清楚是不是真的要全开; 只想开一两个的话,
# 用控制台(Being Studio → Agents → 那一行的「继续」), 它作用范围更小。
#
# 与 agents-off.sh 是同一段代码的两个动词 —— 但刻意写成两个文件而不是
# `agents.sh on|off`: 这两个动作的**后果不对称**(一个省钱、一个花钱), 而 shell 历史里
# 一个打错的参数就会把两者对调。文件名没有这个歧义。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8092}"
ENV_FILE="${AGENT_PLATFORM_ENV:-/etc/agent-platform/.env}"

if [ -z "${OPENAPI_ADMIN_KEY:-}" ] && [ -r "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  OPENAPI_ADMIN_KEY="$(grep -E '^OPENAPI_ADMIN_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
# 见 agents-off.sh 里的同款注释: systemd 以 root 读那个文件(0600), 所以普通用户
# 要多试一次非交互 sudo。
if [ -z "${OPENAPI_ADMIN_KEY:-}" ]; then
  OPENAPI_ADMIN_KEY="$(sudo -n grep -E '^OPENAPI_ADMIN_KEY=' "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
fi
if [ -z "${OPENAPI_ADMIN_KEY:-}" ]; then
  echo "✗ 没有管理钥。三种给法, 任选一种:" >&2
  echo "    OPENAPI_ADMIN_KEY=... bash $0     # 直接从环境变量来" >&2
  echo "    sudo bash $0                      # 读 $ENV_FILE" >&2
  echo "    让 $ENV_FILE 对当前用户可读" >&2
  exit 1
fi

echo "==> 恢复全部 agent ($BASE)"
RESP="$(curl -sS -m 20 -X POST "$BASE/api/v1/openapi/admin/agents/resume-all" \
  -H "X-Admin-Key: $OPENAPI_ADMIN_KEY" -w $'\n%{http_code}')"
CODE="$(printf '%s' "$RESP" | tail -1)"
BODY="$(printf '%s' "$RESP" | sed '$d')"

if [ "$CODE" != "200" ]; then
  echo "✗ HTTP $CODE: $BODY" >&2
  exit 1
fi

echo "    $BODY"
echo ""
echo "已恢复 —— 从现在起重新计入 token 消耗。"
