#!/usr/bin/env bash
# G7 — 仿真 Agent 平台部署: 前端产物 → /var/www/agent + nginx 配置 → reload。
#
# 只部署**前端静态 + nginx 分流**。后端两个服务(8091/8092)是 systemd/jar 的事,
# 本脚本不碰 —— 它也不该悄悄重启一个正在被认知链写状态的进程。
#
#   D1 前置: 依赖 + 构建产物来源
#   D2 前端构建 (--skip-build 可跳过, 用于只改 nginx 的场景)
#   D3 静态产物 rsync 到 /var/www/agent
#   D4 nginx 配置安装 + nginx -t + reload
#   D5 健康检查: /api/health + /docs + 前端 index
#   D6 DNS 体检: being.luxera.top 的公网 A 记录 (**需要人工在 DNS 服务商处添加**)
#
# 用法:
#   bash scripts/deploy.sh                  # 全量
#   bash scripts/deploy.sh --skip-build     # 只同步产物 + nginx
#   bash scripts/deploy.sh --dry-run        # 只体检不动手
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DOMAIN="${DOMAIN:-being.luxera.top}"
WEBROOT="${WEBROOT:-/var/www/agent}"
CONF_SRC="$ROOT/deploy/nginx/being.luxera.top.conf"
CONF_DST="/etc/nginx/conf.d/${DOMAIN}.conf"
SUDO="${SUDO:-sudo}"

SKIP_BUILD=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --dry-run)    DRY_RUN=1 ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

note() { echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
warn() { echo "    ! $*"; }
die()  { echo "    ✗ $*"; exit 1; }
run()  { if [ "$DRY_RUN" = "1" ]; then echo "    [dry-run] $*"; else "$@"; fi; }

# 本脚本要 root(写 /etc/nginx、/var/www、reload nginx), 但**构建不能以 root 跑**。
# `sudo bash scripts/deploy.sh` 会让下面的 npm run build 以 root 身份写
# frontend/dist 与 node_modules 缓存 —— 目录变成 root:root, 之后以 ubuntu 跑
# 任何 npm/gradle 任务都会 Permission denied(仓 1 的 deploy.sh 已经真踩过一次,
# 报错还被 Gradle 包装成"构建缓存损坏", 排查代价很大)。
# 以仓库属主身份构建, 产物权限就始终跟着工作区走。
BUILD_USER="${SUDO_USER:-$(id -un)}"
as_build_user() {
  if [ "$(id -un)" = "$BUILD_USER" ]; then "$@"; else sudo -H -u "$BUILD_USER" -- "$@"; fi
}

echo ""
echo "══════════ G7 仿真 Agent 平台部署 (deploy) ══════════"

# ── D1 前置 ──
note "D1: 前置检查"
[ -f "$CONF_SRC" ] && ok "nginx 配置在版本库里 ($(basename "$CONF_SRC"))" || die "缺 $CONF_SRC"
# nginx 落在 /usr/sbin —— 非登录 shell 的 PATH 里没有 /usr/sbin, 所以
# `command -v nginx` 会假阴性。直接找二进制。
NGINX_BIN="$(command -v nginx || true)"
[ -z "$NGINX_BIN" ] && [ -x /usr/sbin/nginx ] && NGINX_BIN=/usr/sbin/nginx
[ -n "$NGINX_BIN" ] && ok "nginx 在 ($NGINX_BIN)" || die "没有 nginx"
[ -d "$ROOT/frontend" ] && ok "frontend 目录在" || die "缺 frontend/"
[ -d "$ROOT/frontend/node_modules" ] && ok "前端依赖已装" || die "缺 frontend/node_modules —— 先 cd frontend && npm ci"
# 后端没起时部署前端是合法的(静态页照发), 但健康检查会红 —— 先说清楚
if curl -s -m 2 -o /dev/null "http://127.0.0.1:8092/api/health"; then
  ok "openapi:8092 在跑"
else
  warn "openapi:8092 没在跑 —— D5 的健康检查会失败(前端仍会部署)"
fi

# ── D2 前端构建 ──
note "D2: 前端构建"
if [ "$SKIP_BUILD" = "1" ]; then
  ok "--skip-build: 跳过(用现有 dist 或等下重新构建)"
elif [ "$DRY_RUN" = "1" ]; then
  echo "    [dry-run] (cd frontend && npm run build)"
else
  ( cd "$ROOT/frontend" && as_build_user npm run build ) && ok "构建完成 (以 $BUILD_USER)" || die "前端构建失败"
fi
DIST="$ROOT/frontend/dist"
[ "$DRY_RUN" = "1" ] || [ -f "$DIST/index.html" ] || die "缺 $DIST/index.html —— 构建没产出"
[ "$DRY_RUN" = "1" ] || ok "产物就绪: $(du -sh "$DIST" | cut -f1), $(find "$DIST" -type f | wc -l) 个文件"

# ── D3 同步静态产物 ──
note "D3: 同步到 $WEBROOT"
run $SUDO mkdir -p "$WEBROOT"
# --delete: 旧构建的 hash 资源必须清掉, 否则 /var/www/agent 会无限膨胀,
# 且可能留着已修漏洞的旧 JS。
run $SUDO rsync -a --delete "$DIST/" "$WEBROOT/"
run $SUDO chown -R www-data:www-data "$WEBROOT"
ok "已同步 (--delete 清掉旧 hash 资源)"

# ── D4 nginx 配置 ──
note "D4: nginx 分流配置"
if [ "$DRY_RUN" = "1" ]; then
  echo "    [dry-run] $SUDO cp $CONF_SRC $CONF_DST"
else
  if [ -f "$CONF_DST" ] && ! diff -q "$CONF_SRC" "$CONF_DST" >/dev/null; then
    ok "配置有变更, 覆盖前备份到 ${CONF_DST}.bak"
    $SUDO cp "$CONF_DST" "${CONF_DST}.bak"
  fi
  $SUDO cp "$CONF_SRC" "$CONF_DST"
fi
# 先 nginx -t 再 reload —— 配置写错时 reload 会让整个 nginx 停在旧配置上,
# 而 restart 会直接把所有站点打挂。这里必须 -t 通过才动线上。
if [ "$DRY_RUN" = "1" ]; then
  echo "    [dry-run] nginx -t"
else
  $SUDO "$NGINX_BIN" -t 2>&1 | tail -2
  $SUDO systemctl reload nginx && ok "nginx reload 完成"
fi

# ── D5 健康检查 ──
note "D5: 健康检查"
# 用 --resolve 直连本机, 绕开 DNS —— 这一层验的是"nginx 分流配对了没有",
# 不是"公网 DNS 通不通"(那是 D6 的事)。
H="--resolve ${DOMAIN}:443:127.0.0.1"
code() { curl -sk -m 8 -o /dev/null -w '%{http_code}' "$@"; }

# reload 之后旧 worker 仍会短暂服务新连接 —— 头几次请求可能落到旧配置上
# (实测: 首次部署时 /api 连吃三个 302, 几秒后再查全是 200)。
# 不 settle 就断言, 会把一次成功的部署报成失败。所以先等 /api/health 稳定 200。
if [ "$DRY_RUN" != "1" ]; then
  for i in $(seq 1 8); do
    [ "$(code $H "https://${DOMAIN}/api/health")" = "200" ] && break
    sleep 2
  done
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "    [dry-run] curl ${H} https://${DOMAIN}/api/health"
else
  C=$(code $H "https://${DOMAIN}/api/health")
  [ "$C" = "200" ] && ok "/api/health → 8092 (200)" || warn "/api/health 实得 $C (openapi 没起?)"

  # 文档面
  C=$(code $H "https://${DOMAIN}/v3/api-docs")
  [ "$C" = "200" ] && ok "/v3/api-docs 公开可读 (200)" || warn "/v3/api-docs 实得 $C"

  # 文档 UI 的**整条跳转链** —— /docs 是 302, 真正落地的页面在 /swagger-ui/。
  # 只看 /docs 的状态码会被骗过去: 302 本身是"正确"的。
  #
  # 判据是**最终主机名**, 不是 URL 里有没有 "swagger-ui" —— Authelia 的登录页
  # 形如 https://auth.luxera.top/?rd=https%3A%2F%2Fagent...%2Fswagger-ui%2Findex.html,
  # rd 参数里就带着 "swagger-ui", 用子串匹配会把"被弹去登录"判成"文档正常"
  # (这个假阳性在写这条断言时真发生过)。落在 auth 主机上 = 文档面没通。
  DOCS_FINAL=$(curl -skL -m 10 -o /dev/null -w '%{http_code} %{url_effective}' $H "https://${DOMAIN}/docs")
  DOCS_HOST=$(echo "$DOCS_FINAL" | cut -d' ' -f2 | sed -E 's#^https?://([^/]+).*#\1#')
  DOCS_CODE=$(echo "$DOCS_FINAL" | cut -d' ' -f1)
  if [ "$DOCS_HOST" = "auth.luxera.top" ]; then
    warn "/docs 被弹到 Authelia 登录页 —— 文档面没通(检查 /swagger-ui/ 是否被代理 + 8092 是否放行)"
  elif [ "$DOCS_CODE" = "200" ]; then
    ok "/docs → 跟随跳转 → $(echo "$DOCS_FINAL" | cut -d' ' -f2) (200, 未落到 auth 主机)"
  else
    warn "/docs 跟随跳转后: $DOCS_FINAL (期望 200 且不落 auth 主机)"
  fi

  # 未带钥匙的 API 必须是 401 —— 不是 302。
  # 若这里看到 302, 说明 /api/ 被误套了 Authelia(见 nginx 配置文件头的警告),
  # 三方机器客户端将无法接入。这是本脚本唯一会 die 的断言。
  C=$(code $H "https://${DOMAIN}/api/v1/openapi/agents")
  if [ "$C" = "401" ]; then
    ok "/api/v1/openapi/agents 无钥 → 401 (API 面没被 Authelia 拦, 三方可用)"
  else
    die "无钥期望 401, 实得 $C —— /api/ 可能被 Authelia 套住了, 三方客户端会拿到 302 登录跳转"
  fi

  # G8: /api/ 现在按前缀分给两个上游。上面那条验的是 8092 那一半, 这里验 8091 那一半
  # —— 平台自身功能面(companions/admin/v10)必须落在 8091 上。
  #
  # 无 JWT 时 8091 的 anyRequest().authenticated() 回 403。判据是"不是 404 也不是
  # HTML": 404 说明请求跑到了没有该端点的 8092 上(即 /api/ 还指着 8092, 分流没生效);
  # HTML 说明掉进了控制台 SPA 回退 —— 两者都会让前端拿到一份无法 JSON.parse 的东西。
  C=$(code $H "https://${DOMAIN}/api/companions")
  CCT=$(curl -sk -m 8 -o /dev/null -w '%{content_type}' $H "https://${DOMAIN}/api/companions")
  case "$CCT" in
    text/html*) warn "/api/companions 落到 SPA 回退 (HTTP $C) —— /api/ 的 8091 那条 location 没生效" ;;
    *)
      if [ "$C" = "404" ]; then
        warn "/api/companions 实得 404 —— 像是被打到了 8092(那里没有这个端点), 检查 /api/ 的上游"
      else
        ok "/api/companions 无 JWT → $C (平台功能面在 8091; 非 404 非 HTML)"
      fi
      ;;
  esac

  # 静态页必须被 Authelia 拦住(未登录 → 302 到登录页)
  C=$(code $H "https://${DOMAIN}/index.html")
  if [ "$C" = "302" ] || [ "$C" = "401" ]; then
    ok "控制台静态页未登录 → $C (Authelia 前门生效)"
  else
    warn "控制台静态页未登录实得 $C —— 期望 302/401, 管理面可能裸奔"
  fi
fi

# ── D6 DNS 体检 ──
note "D6: 公网 DNS"
if command -v dig >/dev/null 2>&1; then
  A=$(dig +short "$DOMAIN" @223.5.5.5 2>/dev/null | head -1 || true)
else
  A=$(curl -s -m 8 "https://223.5.5.5/resolve?name=$DOMAIN&type=A" \
      | python3 -c 'import sys,json;print(next((x["data"] for x in json.load(sys.stdin).get("Answer",[]) if x.get("type")==1),""))' 2>/dev/null || true)
fi
if [ -n "$A" ]; then
  ok "$DOMAIN → $A"
else
  warn "$DOMAIN 没有公网 A 记录 —— 站内(经 /etc/hosts 或 --resolve)已可用, 但外网访问不到。"
  warn "需要在 DNS 服务商处为该子域名添加 A 记录指向本机公网 IP (124.222.135.75)。"
  warn "luxera.top 不是泛解析, 每个子域名都是单独登记的 —— 这条记录不会自动出现。"
fi

echo ""
if [ "$DRY_RUN" = "1" ]; then
  echo "✅ 体检完成 (dry-run, 未改动任何东西)"
else
  echo "✅ 部署完成"
  echo "   控制台: https://${DOMAIN}/   (Authelia 登录后可见)"
  echo "   文档:   https://${DOMAIN}/docs"
fi
