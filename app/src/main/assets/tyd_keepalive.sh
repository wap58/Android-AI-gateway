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
#
# ★ 状态恢复：只看「用户是否希望网关开着」，而不是无条件拉起。
#   APP 在 files/gateway_on.flag 上维护唯一真值（开启时写、主动停止时删）。
#   进程被杀 / 重启 / 关机时文件来不及改 → 开机后守护照常拉起 = 恢复上次状态；
#   用户主动点了「停止」→ 文件消失，守护不再把它拉回来。

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

# 「用户希望网关运行」的标记文件（App 私有目录，root 可读）
FLAG_DIR=/data/user/0/dev.tyd.app/files
FLAG="$FLAG_DIR/gateway_on.flag"

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

# 用户是否希望网关运行。
# 目录读得到 → 以标记文件为准（存在=开，不存在=用户主动关了）。
# 目录读不到（SELinux/权限异常）→ 判定不确定，保守返回「希望运行」，
# 保持旧行为，宁可多拉一次也不要让守护静默失效。
wanted() {
  if [ -d "$FLAG_DIR" ] && [ -r "$FLAG_DIR" ]; then
    [ -f "$FLAG" ]
    return $?
  fi
  return 0
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
SKIPPED=0
while true; do
  if alive; then
    FAIL=0
    SKIPPED=0
  else
    FAIL=$((FAIL+1))
    if pkg_installed; then
      # 连续 2 次探活失败才重启，避免启动慢被误杀
      if [ "$FAIL" -ge 2 ]; then
        # 用户主动停了网关：不拉起（这是「恢复上次状态」的关键一步）
        if ! wanted; then
          if [ "$SKIPPED" -eq 0 ]; then
            log "网关已由用户主动停止，守护待命"
            SKIPPED=1
          fi
          sleep "$INTERVAL"
          continue
        fi
        SKIPPED=0
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
