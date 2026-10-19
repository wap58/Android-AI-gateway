#!/system/bin/sh
# 天元鼎 · Root 保活守护（由 APP 一键安装，见 service/RootKeeper.kt）
#
# 判据不是「进程在不在」，而是「网关答不答」—— 进程活着但引擎死了才是最常见故障。
#
# 探活工具按可用性依次回退：
#   1. curl（Android 10+ /system/bin/curl 自带，实测可用）
#   2. wget（busybox 或 toybox 提供）
#   3. /dev/tcp 端口连通性（shell 内建，兜底）
# 不写死 busybox 裸名：多数设备 busybox 不在 PATH 里（KernelSU 的在
# /data/adb/ksu/bin/），写死会静默失败，表现为「守护在跑但永远拉起不了服务」。

PKG="dev.tyd.app"
# 注意：服务类的包名是代码命名空间 dev.aigw.app，不是 applicationId dev.tyd.app。
# 写成 "dev.tyd.app/.service.GatewayService" 会被展开成 dev.tyd.app.service.GatewayService，
# 那个类不存在 —— am 直接报 "Error: Not found; no service started."，守护永远拉不起来。
SVC="dev.tyd.app/dev.aigw.app.service.GatewayService"
PORT=8877
TAG="tyd_keepalive"
INTERVAL=20
LOG=/data/adb/tyd_keepalive.log
MAXLOG=256000

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
  if [ -f "$LOG" ]; then
    sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    [ "$sz" -gt "$MAXLOG" ] && tail -c 64000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

alive() {
  if command -v curl >/dev/null 2>&1; then
    curl -s -m 3 -o /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null
    return $?
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null
    return $?
  fi
  # 兜底：只验端口通不通（无法确认网关真的活着，但比没有强）
  (echo > /dev/tcp/127.0.0.1/$PORT) >/dev/null 2>&1
}

pkg_installed() {
  [ -n "$(pm path "$PKG" 2>/dev/null)" ]
}

# 等系统服务就绪（开机早期 am/pm 还没起来）
wait_boot() {
  i=0
  while [ $i -lt 60 ]; do
    if service check activity 2>/dev/null | grep -q found; then return 0; fi
    sleep 2; i=$((i+1))
  done
  return 1
}

log "=== 守护启动 (pid $$) ==="
wait_boot

FAIL=0
while true; do
  if alive; then
    FAIL=0
  else
    FAIL=$((FAIL+1))
    if pkg_installed; then
      # 连续 2 次探活失败才重启，避免启动慢被误杀
      if [ "$FAIL" -ge 2 ]; then
        log "探活失败 ${FAIL} 次 -> 拉起服务"
        am start-foreground-service -n "$SVC" >/dev/null 2>&1
        sleep 8
        if alive; then
          log "已恢复"
          FAIL=0
        else
          log "拉起后仍未响应，下轮继续"
        fi
      fi
    else
      log "包未安装，等待"
    fi
  fi
  sleep "$INTERVAL"
done
