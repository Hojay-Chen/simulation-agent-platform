#!/usr/bin/env bash
# 把平台上**全部** agent 停下来 —— 运维口的一键止血。
#
# 为什么需要它: 这些 agent 是持续运转的。没人说话时, 定时任务照样在推进它们的一生,
# 而每一步都可能调用模型 —— 空转的成本与"有人在聊天"是同一个量级。开发、测试、排查
# 期间让它们跑着, 就是在按小时烧钱。
#
# 它做的事就是调 8092 管理密钥面的 pause-all。**为什么不是直接改库**: 那个端点会
# 逐行比对并返回真正变化的行数, 于是"我按了两下"与"我按了一下"在输出上分得清;
# 而一条 UPDATE 只会告诉你 "UPDATE 98"。
#
# 用法:
#   bash scripts/agents-off.sh                 # 停全部
#   bash scripts/agents-on.sh                  # 恢复全部
#   OPENAPI_ADMIN_KEY=... bash scripts/agents-off.sh
#
# 管理钥只在环境变量里。没配就从 /etc/agent-platform/.env 读 —— 与 check-console.sh
# 同一套约定, 密钥不进源码、不进 manifest。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8092}"
ENV_FILE="${AGENT_PLATFORM_ENV:-/etc/agent-platform/.env}"

if [ -z "${OPENAPI_ADMIN_KEY:-}" ] && [ -r "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  OPENAPI_ADMIN_KEY="$(grep -E '^OPENAPI_ADMIN_KEY=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
# 那个文件是 root 0600(systemd 以 root 读它), 于是普通用户跑本脚本时上面那条读不到。
# 试一次**非交互** sudo(-n): 有免密就用, 没有就干脆失败 —— 在这里弹一个密码提示,
# 会让"一条命令把全部 agent 停下来"这件事在脚本里不可用(而它正是本脚本的全部意义)。
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

echo "==> 停止全部 agent ($BASE)"
RESP="$(curl -sS -m 20 -X POST "$BASE/api/v1/openapi/admin/agents/pause-all" \
  -H "X-Admin-Key: $OPENAPI_ADMIN_KEY" -w $'\n%{http_code}')"
CODE="$(printf '%s' "$RESP" | tail -1)"
BODY="$(printf '%s' "$RESP" | sed '$d')"

if [ "$CODE" != "200" ]; then
  echo "✗ HTTP $CODE: $BODY" >&2
  exit 1
fi

echo "    $BODY"
echo ""
echo "已停止。注意: 这**不删任何东西** —— 记忆、关系、未说完的话全都留在原处,"
echo "下次 agents-on.sh 之后从停下的那一刻接着走。"
