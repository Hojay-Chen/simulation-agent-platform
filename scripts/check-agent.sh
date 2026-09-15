#!/usr/bin/env bash
# 仓 2 边界守卫 — simulation-agent-platform 的静态依赖检查(仿仓 1 check-v10.sh)。
#
# 它守什么:
#   1. 各 Gradle 项目"拥有"的包两两不相交(split package 检查);
#   2. 仿真 Agent 平台(digital-human)不认识 chat / application 世界的任何实现 ——
#      对外认知只允许两条: contract artifact(com.luxera.companion.contracts.*) 与
#      本仓 common;
#   3. common 是底座: 不认识 digital-human 的包;
#   4. Gradle 依赖图与声明一致。
#
# 与 ArchUnit 的关系: 本脚本是 grep 第一道防线(编译前就能发现问题);
# ModuleBoundary/DhApplicationKnowledge 等 ArchUnit 测试随 gradle test 跑第二道。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fail=0
note() { echo "  $*" >&2; }
ok()   { note "✓ $*"; }
bad()  { note "✗ $*"; fail=1; }

# 项目 → 该项目 src/main/java 下 com.luxera.companion.<X> 的 <X> 集合
project_dir() {
  case "$1" in
    digital-human) echo "backend/digital-human-platform";;
    *) echo "$1";;
  esac
}
owned_packages() {
  local module="$1"
  local dir="$ROOT/$(project_dir "$module")/src/main/java/com/luxera/companion"
  [[ -d "$dir" ]] || return 0
  find "$dir" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
}

PROJECTS=(common digital-human server openapi)

echo "== 1) 包归属互斥(split package 检查) =="
declare -A OWNER
for m in "${PROJECTS[@]}"; do
  while read -r pkg; do
    [[ -n "$pkg" ]] || continue
    if [[ -n "${OWNER[$pkg]:-}" ]]; then
      bad "包 com.luxera.companion.$pkg 同时存在于 ${OWNER[$pkg]} 与 $m"
    else
      OWNER[$pkg]="$m"
    fi
  done < <(owned_packages "$m")
done
[[ "$fail" -eq 0 ]] && ok "包归属互斥(${#OWNER[@]} 个顶层包分属 ${#PROJECTS[@]} 个项目)"

echo "== 2) digital-human 不引用仓外世界(只许 contracts.* 与本仓包) =="
# DH 源码里出现的 com.luxera.companion.* import, 除 contracts 与本仓四项目拥有的包
# 之外都不许有。白名单由各项目源码树推导, 加新包不用改本脚本。
DH_DIR="$ROOT/$(project_dir digital-human)"
ALLOWED="contracts"
for m in "${PROJECTS[@]}"; do
  while read -r pkg; do
    [[ -n "$pkg" ]] && ALLOWED="$ALLOWED|$pkg"
  done < <(owned_packages "$m")
done
hits=$(grep -rhoP 'import com\.luxera\.companion\.\K[a-z0-9]+' "$DH_DIR/src" --include=*.java 2>/dev/null \
  | sort -u | grep -vE "^($ALLOWED)$" || true)
if [[ -n "$hits" ]]; then
  bad "digital-human 引用了仓外/不许认识的顶层包:"
  echo "$hits" | sed 's|^|      |' >&2
else
  ok "digital-human 只认识 contracts.* 与本仓 ${#PROJECTS[@]} 个项目的包"
fi

echo "== 3) common 不认识 digital-human 的业务包 =="
COMMON_DIR="$ROOT/common/src"
dh_pkgs=$(owned_packages digital-human | paste -sd'|' -)
hits=$(grep -rnE "import com\\.luxera\\.companion\\.($dh_pkgs)\\." "$COMMON_DIR" --include=*.java 2>/dev/null | head -5 || true)
if [[ -n "$hits" ]]; then
  bad "common 引用了 digital-human 的包:"
  echo "$hits" | sed 's|^|      |' >&2
else
  ok "common 不引用 digital-human 的包"
fi

echo "== 4) Gradle 依赖图 =="
# 唯一合法方向: server/openapi → {common, digital-human}, digital-human → common,
# common → contract artifact(不依赖任何仓内项目)
check_gradle() {
  local project="$1" expect="$2"
  local build_file
  case "$project" in
    digital-human) build_file="$ROOT/backend/digital-human-platform/build.gradle";;
    *) build_file="$ROOT/$project/build.gradle";;
  esac
  local has_dep
  has_dep=$(grep -c "project(':$expect')" "$build_file" 2>/dev/null || true)
  if [[ "$has_dep" -gt 0 ]]; then
    ok "$project 依赖 $expect (合法)"
  else
    bad "$project 应依赖 $expect (build.gradle 没找到 project(':$expect'))"
  fi
}
check_gradle digital-human common
check_gradle server common
check_gradle server digital-human
# common 依赖 contract artifact(mavenLocal), 不依赖任何仓内项目
if grep -qE "project\(':" "$ROOT/common/build.gradle"; then
  bad "common/build.gradle 出现仓内项目依赖 —— common 只许依赖外部 contract artifact"
else
  ok "common 零仓内项目依赖(依赖 contract artifact)"
fi
# openapi G4 起依赖 digital-human(编译期整模块), 但启动类用 includeFilters
# 白名单只扫 persona 闭包的薄件(CompanionService/PersonaService/PersonaCompiler/
# LlmRouter/RelationshipService/PersonService/AgentStateService/EmotionReducer)。
# 静态断言: 认知链包(runtime/cognition/life/behavior/emotion/memory/attention/
# appraisal/proactive/...)绝不进 openapi 的 includeFilters 正则 —— 否则会在
# 8092 进程里复制一份 server:8091 的认知链, 两边争抢同一批表与定时任务。
if grep -q "project(':digital-human')" "$ROOT/openapi/build.gradle"; then
  ok "openapi 依赖 digital-human(G4: persona 闭包薄件)"
else
  bad "openapi 未依赖 digital-human —— G4 后业务端点需要它"
fi
# 认知链包不得出现在 openapi 启动类的扫描集里
COGNITION_PACKS="runtime cognition life behavior emotion memory attention appraisal proactive plan openloop experience reality relationship_purpose selfmodel_full scheduler interaction digitalhuman tool"
LEAK=""
for pkg in $COGNITION_PACKS; do
  # includeFilters 的 pattern 字符串里若出现 com.luxera.companion.<pkg> 即为泄漏
  if grep -qE "companion\\.${pkg}[\.\)]" "$ROOT/openapi/src/main/java/com/luxera/agentopenapi/AgentOpenApiApplication.java"; then
    LEAK="$LEAK $pkg"
  fi
done
if [[ -z "$LEAK" ]]; then
  ok "openapi 扫描白名单不含任何认知链包(CompanionSchedule/AgentRuntime 等不进 8092)"
else
  bad "openapi 扫描白名单泄漏了认知链包:$LEAK —— 会在 8092 复制 server:8091 的认知链"
fi

# contract artifact 可解析(发布过 publishToMavenLocal)
if [[ -f "$HOME/.m2/repository/com/luxera/contract/1.0.0/contract-1.0.0.jar" ]]; then
  ok "contract:1.0.0 artifact 在 mavenLocal(仓 1 执行 gradle :contract:publishToMavenLocal)"
else
  bad "mavenLocal 缺 com.luxera:contract:1.0.0 —— 先在仓 1(chat-platform) 发布"
fi

if [[ "$fail" -eq 0 ]]; then
  echo "check-agent OK"
else
  echo "check-agent FAILED"
  exit 1
fi
