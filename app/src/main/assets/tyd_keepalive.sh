#!/system/bin/sh
# 天元鼎 · Root 保活守护（由 APP 一键安装，见 service/RootKeeper.kt）
#
# ============================ 设计要点 ============================
# 1) 判据不是「进程在不在」，而是「网关答不答」
#    进程活着但引擎死了（HTTP 端口没人听）才是最常见故障，光看进程会漏。
#
# 2) 双通道判活 + 分级响应（不是傻等两次）
#    - 进程没了          → 立刻拉起（没有任何理由等，进程不在网关必然不通）
#    - 进程在但探活失败  → 连续 2 次才拉起（可能正在冷启动/处理大请求）
#
# 3) ★ 状态持久化（本守护的核心职责）
#    状态存 /data/adb/tyd_keepalive.state（"on"/"off"），root 域持久文件：
#    - 不受 App 数据清除 / 加密存储未解锁影响
#    - 关机重启后依旧存在 → 开机即可恢复上次状态
#    - App 主动启停时通过 su 改写（见 RootKeeper.writeState）
#    读取优先级：state 文件 > App 私有目录 gateway_on.flag > 默认 on
#    用户主动点了「停止」→ 状态 off，守护绝不拉起；
#    进程被杀 / 意外退出 / 重启 → 状态仍是 on，守护立刻恢复 = 保持上次状态。
#
# 4) 探活工具按可用性回退，不写死 busybox 裸名
#    （多数设备 busybox 不在 PATH，写死会静默失败：守护在跑但永远拉起不了）
#
# ============================ 参数 ================================
PKG="dev.tyd.app"
# 注意：服务类的包名是代码命名空间 dev.aigw.app，不是 applicationId dev.tyd.app。
# 写成 "dev.tyd.app/.service.GatewayService" 会被展开成 dev.tyd.app.service.GatewayService，
# 那个类不存在 —— am 直接报 "Error: Not found; no service started."，守护永远拉不起来。
SVC="dev.tyd.app/dev.aigw.app.service.GatewayService"
PORT=8877
TAG="tyd_keepalive"
# 探活间隔：5 秒。网关对客户端是「连不上就失败」，恢复越慢损失越大。
INTERVAL=5
# 拉起后等待端口就绪的上限（秒）
BOOT_WAIT=20
LOG=/data/adb/tyd_keepalive.log
MAXLOG=256000

# ★ 状态真值（root 域，持久）
STATE=/data/adb/tyd_keepalive.state
# App 侧兜底标记（私有目录，root 可读）
FLAG_DIR=/data/user/0/dev.tyd.app/files
FLAG="$FLAG_DIR/gateway_on.flag"

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
  if [ -f "$LOG" ]; then
    sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    [ "$sz" -gt "$MAXLOG" ] && tail -c 64000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

# ---------------------------------------------------------------- 探活
alive() {
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 3 -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null
    return $?
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null
    return $?
  fi
  # 兜底：只验端口通不通（无法确认网关真活着，但比没有强）
  (echo > /dev/tcp/127.0.0.1/$PORT) >/dev/null 2>&1
}

# 应用进程是否还在（只看主进程，不看 :xxx 子进程）
app_running() {
  if command -v pidof >/dev/null 2>&1; then
    pidof "$PKG" >/dev/null 2>&1
    return $?
  fi
  ps -A 2>/dev/null | grep -v grep | grep -q " $PKG\$"
}

pkg_installed() {
  [ -n "$(pm path "$PKG" 2>/dev/null)" ]
}

# ---------------------------------------------------------------- 状态
# 用户是否希望网关运行。
#   1. App 私有目录可读时，以标记文件为准 —— 这是 App 实时真值，
#      用户点「停止服务」立刻删掉，守护下一轮（5 秒内）就停手，无需 root 改写任何东西。
#   2. 目录读不到（开机早期加密分区未就绪 / SELinux / 被清数据）→ 用 root 域状态文件。
#      root 域文件在 /data/adb，重启、清 App 数据都不受影响，是「状态持久化」的落点。
#   3. 两者都没有 → 默认 on（守护既然装了，就是想要它保活）。
wanted() {
  if [ -d "$FLAG_DIR" ] && [ -r "$FLAG_DIR" ]; then
    [ -f "$FLAG" ]
    return $?
  fi
  if [ -f "$STATE" ]; then
    [ "$(cat "$STATE" 2>/dev/null | tr -d ' \r\n')" = "off" ] && return 1
    return 0
  fi
  return 0
}

set_state() {
  echo "$1" > "$STATE" 2>/dev/null
  chmod 644 "$STATE" 2>/dev/null
}

# ---------------------------------------------------------------- 拉起
# 等系统服务就绪（开机早期 am/pm 还没起来）
wait_boot() {
  i=0
  while [ $i -lt 60 ]; do
    if service check activity 2>/dev/null | grep -q found; then return 0; fi
    sleep 2; i=$((i+1))
  done
  return 1
}

# 拉起网关并等待端口就绪
kick() {
  [ -n "$1" ] && log "拉起原因：$1"
  am start-foreground-service -n "$SVC" >/dev/null 2>&1
  i=0
  while [ $i -lt "$BOOT_WAIT" ]; do
    sleep 1
    if alive; then
      log "已恢复（等待 ${i}s）"
      return 0
    fi
    i=$((i+1))
  done
  # 第二轮：进程仍没起来时补发一次（部分 ROM 首发被拦、第二次才放行）
  if ! app_running; then
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
    i=0
    while [ $i -lt "$BOOT_WAIT" ]; do
      sleep 1
      if alive; then
        log "已恢复（第二轮，等待 ${i}s）"
        return 0
      fi
      i=$((i+1))
    done
  fi
  log "拉起后仍未响应，下轮继续"
  return 1
}

# ---------------------------------------------------------------- 主循环
log "=== 守护启动 (pid $$) 状态=$( [ -f "$STATE" ] && cat "$STATE" || echo 未记录 ) ==="
wait_boot

# 状态文件未建立时落盘一次：之后 App 有 root 时会在启停时同步改写，
# 没 root 也不影响（脚本优先读 App 的标记文件），但重启后至少有一份持久真值。
if [ ! -f "$STATE" ]; then
  if wanted; then set_state on; else set_state off; fi
  log "初始化状态文件：$(cat "$STATE" 2>/dev/null)"
fi

FAIL=0
IDLE_LOGGED=0
while true; do
  if alive; then
    FAIL=0
    IDLE_LOGGED=0
    sleep "$INTERVAL"
    continue
  fi

  if ! pkg_installed; then
    [ "$IDLE_LOGGED" -eq 0 ] && log "包未安装，等待" && IDLE_LOGGED=1
    sleep "$INTERVAL"
    continue
  fi

  # 用户主动停止：待命，不拉起
  if ! wanted; then
    [ "$IDLE_LOGGED" -eq 0 ] && log "网关已由用户主动停止，守护待命" && IDLE_LOGGED=1
    FAIL=0
    sleep "$INTERVAL"
    continue
  fi
  IDLE_LOGGED=0

  # 进程没了：立刻拉起，不必等第二次探活
  if ! app_running; then
    log "应用进程不存在，网关必然不通 -> 立即拉起"
    kick "进程缺失"
    FAIL=0
    sleep "$INTERVAL"
    continue
  fi

  # 进程在、端口不通：连续 2 次才动手（可能正在冷启动）
  FAIL=$((FAIL+1))
  if [ "$FAIL" -ge 2 ]; then
    log "进程在但探活连续失败 ${FAIL} 次 -> 拉起服务"
    kick "引擎无响应"
    FAIL=0
  fi
  sleep "$INTERVAL"
done
