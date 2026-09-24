#!/usr/bin/env bash
# =============================================================================
# pause-at-day.sh —— 盯住 AgentSoftware 的 Web UI，模拟时间一到第 N 天就自动暂停
#
# 为什么需要：仿真一直开着会继续烧 LLM 额度。这个脚本只轮询 /api/state（只读、
# 几乎不耗资源），一旦 day >= 目标天就 POST /api/pause，把仿真停在原地 ——
# 不杀进程、不丢上下文，第二天点 Web UI 的 Resume（或 curl /api/resume）就能接着跑。
#
# 暂停的确切含义（与 TimeBus 实现一致）：
#   * 时钟停跳 —— 排期事件不会到点、班次边界（上班/下班广播）不再触发，
#     所以**不会再产生新的任务**；
#   * 但**已经在跑的那条任务会跑完**，各角色队列里已经排上的事件也会被处理掉
#     （worker 不看 paused），所以暂停后还会再花一小会儿才彻底安静下来 —— 这是
#     有限的一小段，不是无限烧。
#
# 用法：
#   tools/pause-at-day.sh                          # 默认第 8 天 · http://localhost:8787 · 每 15s 一次
#   tools/pause-at-day.sh --day 9 --interval 30
#   tools/pause-at-day.sh --max-minutes 480        # 兜底：墙钟跑满 8 小时也暂停
#   tools/pause-at-day.sh --dry-run --once         # 只看一眼当前状态，什么都不做
#
# 后台跑（推荐，晚上丢上去就行）：
#   nohup tools/pause-at-day.sh --day 8 --guard > /tmp/pause-at-day.log 2>&1 &
#   tail -f /tmp/pause-at-day.log
#   # 第二天在 Web UI 点 Resume，或者： curl -X POST localhost:8787/api/resume
#
# 参数（括号里是同名环境变量）：
#   --day N           (PAUSE_AT_DAY)          到达/超过这个模拟日就暂停。默认 8
#   --at HH:MM        (PAUSE_AT_TIME)         可选：在第 N-1 天的 HH:MM 就提前暂停。
#                                             用来避开跨天前的大规模"收工"（每个角色都要写
#                                             每日总结，是全天最烧 token 的一波）。例：--day 8 --at 17:30
#   --url URL         (AGENTSOFTWARE_WEB_URL) Web UI 地址。默认 http://localhost:8787
#   --interval SEC    (PAUSE_POLL_SECONDS)    轮询间隔秒。默认 15
#   --max-minutes M   (PAUSE_MAX_MINUTES)     墙钟兜底：跑了 M 分钟还没到目标天也暂停。0=关，默认 0
#   --max-failures N  (PAUSE_MAX_FAILURES)    连续取不到状态 N 次就放弃退出。默认 40（15s × 40 ≈ 10 分钟）
#   --guard                                   暂停后继续盯着，一旦被 resume 就再暂停（防甲方互动把仿真唤醒）
#   --exit-after-pause                        暂停成功就退出（默认行为，写出来只为对称）
#   --dry-run                                 只打印，不真的暂停
#   --once                                    只查一次就退出（手动确认用；此时若该暂停会真的暂停）
#   -h | --help                               打印本说明
#
# 退出码：0=已暂停/正常结束  1=参数或环境错误  2=连不上 Web UI 放弃  130=被 Ctrl-C 中断（不会暂停）
# =============================================================================
set -u
set -o pipefail

TARGET_DAY="${PAUSE_AT_DAY:-8}"
AT="${PAUSE_AT_TIME:-}"
URL="${AGENTSOFTWARE_WEB_URL:-http://localhost:8787}"
INTERVAL="${PAUSE_POLL_SECONDS:-15}"
MAX_MINUTES="${PAUSE_MAX_MINUTES:-0}"
MAX_FAILURES="${PAUSE_MAX_FAILURES:-40}"
DRY_RUN=0
ONCE=0
GUARD=0

usage() { sed -n '2,/^set -u$/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

# ── 参数 ────────────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --day)           TARGET_DAY="${2:-}"; shift 2 ;;
    --day=*)         TARGET_DAY="${1#*=}"; shift ;;
    --at)            AT="${2:-}"; shift 2 ;;
    --at=*)          AT="${1#*=}"; shift ;;
    --url)           URL="${2:-}"; shift 2 ;;
    --url=*)         URL="${1#*=}"; shift ;;
    --interval)      INTERVAL="${2:-}"; shift 2 ;;
    --interval=*)    INTERVAL="${1#*=}"; shift ;;
    --max-minutes)   MAX_MINUTES="${2:-}"; shift 2 ;;
    --max-minutes=*) MAX_MINUTES="${1#*=}"; shift ;;
    --max-failures)  MAX_FAILURES="${2:-}"; shift 2 ;;
    --max-failures=*) MAX_FAILURES="${1#*=}"; shift ;;
    --guard)         GUARD=1; shift ;;
    --exit-after-pause) GUARD=0; shift ;;
    --dry-run)       DRY_RUN=1; shift ;;
    --once)          ONCE=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "未知参数：$1（--help 看说明）" >&2; exit 1 ;;
  esac
done

URL="${URL%/}"
require_int() {  # $1=名字 $2=值 $3=最小值
  case "$2" in
    ''|*[!0-9]*) echo "$1 必须是整数，收到：'$2'" >&2; exit 1 ;;
  esac
  [ "$2" -ge "$3" ] || { echo "$1 不能小于 $3，收到：$2" >&2; exit 1; }
}
require_int --day "$TARGET_DAY" 1
require_int --interval "$INTERVAL" 1
require_int --max-minutes "$MAX_MINUTES" 0
require_int --max-failures "$MAX_FAILURES" 0
if [ -n "$AT" ]; then
  case "$AT" in
    [0-2][0-9]:[0-5][0-9]) ;;
    *) echo "--at 要写成 HH:MM（例如 17:30），收到：'$AT'" >&2; exit 1 ;;
  esac
fi
case "$URL" in http://*|https://*) ;; *) echo "--url 要以 http:// 或 https:// 开头：$URL" >&2; exit 1 ;; esac
command -v curl >/dev/null 2>&1 || { echo "需要 curl" >&2; exit 1; }

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

# 取一次状态：stdout = "day tick date time paused"，失败返回非 0
get_state() {
  local body
  body=$(curl -sS -m 10 "$URL/api/state" 2>/dev/null) || return 1
  [ -n "$body" ] || return 1
  # 优先用 python3 正经解析；没有 python3 就退回 sed/grep（/api/state 的 JSON 很扁）
  if command -v python3 >/dev/null 2>&1; then
    if printf '%s' "$body" | python3 -c '
import json,sys
d = json.load(sys.stdin)
print(d.get("day"), d.get("tick"), d.get("date"), d.get("time"),
      "true" if d.get("paused") else "false")
' 2>/dev/null; then
      return 0
    fi
  fi
  local day tick sdate stime paused
  day=$(printf '%s' "$body"   | sed -n 's/.*"day":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
  tick=$(printf '%s' "$body"  | sed -n 's/.*"tick":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
  sdate=$(printf '%s' "$body" | sed -n 's/.*"date":[[:space:]]*"\([^"]*\)".*/\1/p')
  stime=$(printf '%s' "$body" | sed -n 's/.*"time":[[:space:]]*"\([^"]*\)".*/\1/p')
  if printf '%s' "$body" | grep -q '"paused":[[:space:]]*true'; then paused=true; else paused=false; fi
  [ -n "$day" ] || return 1
  printf '%s %s %s %s %s\n' "$day" "${tick:-?}" "${sdate:-?}" "${stime:-?}" "$paused"
}

# 真的去暂停；成功（并复核到 paused=true）返回 0
pause_now() {
  local why="$1" i state day
  for i in 1 2 3 4 5; do
    log "→ POST $URL/api/pause（$why，第 $i 次）"
    curl -sS -m 10 -X POST -H 'Content-Type: application/json' --data '{}' \
         "$URL/api/pause" >/dev/null 2>&1
    sleep 2
    if state=$(get_state); then
      day=${state%% *}
      if [ "${state##* }" = true ]; then
        log "✅ 已暂停：sim 第 $day 天（$why）"
        log "   继续跑：Web UI 点 Resume，或 curl -X POST $URL/api/resume"
        return 0
      fi
    fi
    log "⚠ 复核时还没看到 paused=true，重试"
  done
  log "✖ 发了 5 次 /api/pause 仍没确认暂停，请手动检查 Web UI"
  return 1
}

log "启动：目标=第 $TARGET_DAY 天$([ -n "$AT" ] && echo "（或第 $((TARGET_DAY-1)) 天 $AT 提前）") · $URL · 每 ${INTERVAL}s 查一次$([ "$MAX_MINUTES" -gt 0 ] && echo " · 墙钟兜底 ${MAX_MINUTES} 分钟")$([ "$DRY_RUN" = 1 ] && echo " · dry-run")$([ "$GUARD" = 1 ] && echo " · guard 模式")"
if state=$(get_state); then
  log "当前：sim 第 ${state%% *} 天（$(echo "$state" | cut -d' ' -f3,4)）paused=$(echo "$state" | awk '{print $5}')"
else
  log "⚠ 一开始就连不上 $URL/api/state —— 仍然继续等（仿真可能还没起来）"
fi

started=$(date +%s)
fails=0
paused_once=0

trap 'log "收到中断信号，退出（没有暂停）"; exit 130' INT TERM

guard_ticks=0

while :; do
  if state=$(get_state); then
    fails=0
    read -r day tick sdate stime paused <<<"$state"

    reason=""
    if [ "$day" -ge "$TARGET_DAY" ]; then
      reason="已到第 $day 天（目标第 $TARGET_DAY 天）"
    elif [ -n "$AT" ] && [ "$day" -eq $((TARGET_DAY - 1)) ] && { [ "$stime" = "$AT" ] || [[ "$stime" > "$AT" ]]; }; then
      reason="第 $day 天已经到 $AT（比第 $TARGET_DAY 天提前，避开跨天前的收工）"
    elif [ "$MAX_MINUTES" -gt 0 ] && [ $(( ($(date +%s) - started) / 60 )) -ge "$MAX_MINUTES" ]; then
      reason="墙钟兜底：已经跑了 ${MAX_MINUTES} 分钟"
    fi

    if [ -n "$reason" ] && [ "$paused" = true ]; then
      # 已经是暂停状态：如果本来就是它干的（paused_once=1）就安静地继续盯着，
      # 否则（别人先暂停的/我们启动时就已经暂停）直接收工，不重复 POST。
      if [ "$paused_once" = 0 ]; then
        log "✅ 它已经是暂停状态（$reason），退出"
        exit 0
      fi
      guard_ticks=$((guard_ticks + 1))
      [ $((guard_ticks % 4)) -eq 1 ] && log "guard：仍是暂停状态（sim 第 $day 天），继续盯着"
    elif [ -n "$reason" ]; then
      log "sim 第 $day 天 $sdate $stime · paused=$paused"
      [ "$paused_once" = 1 ] && log "↻ 有人把它 resume 了（仍在第 $day 天之后）→ 再暂停一次"
      if [ "$DRY_RUN" = 1 ]; then
        log "[dry-run] 这里本该暂停：$reason"
        exit 0
      fi
      pause_now "$reason" || exit 1
      paused_once=1
      guard_ticks=0
      if [ "$GUARD" = 0 ]; then
        exit 0
      fi
      log "guard：继续盯着（谁 resume 我就再暂停）"
    else
      log "sim 第 $day 天 $sdate $stime · paused=$paused"
      if [ "$ONCE" = 1 ]; then
        log "还没到第 $TARGET_DAY 天（现在第 $day 天）—— --once 直接退出"
        exit 0
      fi
    fi
  else
    fails=$((fails + 1))
    log "⚠ 拿不到 $URL/api/state（连续 $fails 次）"
    if [ "$MAX_FAILURES" -gt 0 ] && [ "$fails" -ge "$MAX_FAILURES" ]; then
      log "✖ 连续 $fails 次取不到状态，放弃退出（仿真大概已经结束了，没什么可暂停的）"
      exit 2
    fi
  fi
  sleep "$INTERVAL"
done
