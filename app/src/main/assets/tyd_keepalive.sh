#!/system/bin/sh
# 天元鼎 · Root 保活守护（由 APP 一键安装，见 service/RootKeeper.kt）
#
# ======================== 设计要点 ========================
# 1) 判据不是「进程在不在」，而是「网关答不答」。进程活着但端口没人听才是最常见故障。
#
# 2) ★ 探活绝不依赖单一工具（APatch 上的真实故障就出在这里）
#    之前只试 curl → wget → /dev/tcp：
#      - Android 10 以下没有 /system/bin/curl
#      - 很多设备 busybox 不在 PATH 里（APatch 的在 /data/adb/ap/bin/）
#      - /dev/tcp 是 bash/ksh 的语法，Android 的 mksh 根本不支持
#    三条全挂 → 探活恒为「死」→ 每 5 秒拉起一次，界面显示「拉起了但仍无响应」。
#
#    现在按「绝对路径 → 框架自带 busybox → toybox → /proc/net/tcp」依次回退，
#    最后一级直接读内核 TCP 表判断端口是否 LISTEN，纯文本、任何 Android 都有。
#
# 3) 双通道 + 分级响应：
#    - 进程没了        → 立刻拉起
#    - 进程在但探活失败 → 连续 2 次才拉起（可能正在冷启动）
#
# 4) 状态持久化：/data/adb/tyd_keepalive.state（on/off），重启/清 App 数据都不丢
#
# ======================== 参数 ============================
PKG="dev.tyd.app"
# 注意：服务类的包名是代码命名空间 dev.aigw.app，不是 applicationId dev.tyd.app。
# 写成 "dev.tyd.app/.service.GatewayService" 会被展开成 dev.tyd.app.service.GatewayService，
# 那个类不存在 —— am 直接报 "Error: Not found; no service started."，守护永远拉不起来。
SVC="dev.tyd.app/dev.aigw.app.service.GatewayService"
# 脚本版本：App 用它与 assets 里的版本比对，不一致时提示「重装」。
# 改脚本逻辑务必同步改这一行，否则用户装的新 APK 仍在跑旧脚本。
VER="2026-10-21.2"
PORT=8877
INTERVAL=5
BOOT_WAIT=20
LOG=/data/adb/tyd_keepalive.log
MAXLOG=256000

STATE=/data/adb/tyd_keepalive.state
FLAG_DIR=/data/user/0/dev.tyd.app/files
FLAG="$FLAG_DIR/gateway_on.flag"

# 端口 8877 的十六进制（/proc/net/tcp 用大写十六进制、端口在后半段）
PORT_HEX=$(printf '%04X' "$PORT")

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
  if [ -f "$LOG" ]; then
    sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    [ "$sz" -gt "$MAXLOG" ] && tail -c 64000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

# ---------------------------------------------------------- 探活
# _http_ok <命令路径...>：用指定工具请求 healthz，200 即成功
_curl_ok() {
  "$1" -s -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/healthz" 2>/dev/null | grep -q 200
}

# 端口是否处于 LISTEN：读 /proc/net/tcp(6)，纯文本判断，不依赖任何网络工具。
# 格式：sl local_address rem_address st ...，st=0A 表示 LISTEN，local 形如 0100007F:22AD
_port_listening() {
  # 用 awk 解析而不是 grep 拼字符串：/proc/net/tcp 的行格式随内核对齐方式变化，
  # 直接匹配 "端口 + 空格" 会因为多余空格/字段宽度不同而漏判。
  # $2 = local_address(HEX8:HEX4)，$3 = rem_address，$4 = st（0A = LISTEN）
  awk -v p="$PORT_HEX" '
    NR>1 {
      split($2, a, ":");
      if (toupper(a[2]) == p && toupper($4) == "0A") { found=1; exit }
    }
    END { exit(found ? 0 : 1) }
  ' /proc/net/tcp /proc/net/tcp6 2>/dev/null && return 0
  return 1
}

# 记录最近一次成功的探活通道，供排障（只在新通道生效时写一行日志）
PROBE_CH=""
probe_changed() {
  [ "$1" = "$PROBE_CH" ] && return
  PROBE_CH="$1"
  log "探活通道：$1"
}

alive() {
  # 1) curl 绝对路径（Android 10+ 自带；不走 PATH，避免 su 环境 PATH 精简）
  for c in /system/bin/curl /system/xbin/curl /data/adb/ap/bin/curl /data/adb/ksu/bin/curl; do
    if [ -x "$c" ]; then
      if _curl_ok "$c"; then probe_changed "$c"; return 0; fi
    fi
  done
  if command -v curl >/dev/null 2>&1; then
    if _curl_ok curl; then probe_changed "curl(PATH)"; return 0; fi
  fi

  # 2) 各框架自带的 busybox wget（PATH 里通常没有，必须写绝对路径）
  for b in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox \
           /system/xbin/busybox /system/bin/busybox /data/adb/ap/bin/wget; do
    if [ -x "$b" ]; then
      case "$b" in
        *busybox)
          if "$b" wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then
            probe_changed "$b wget"; return 0
          fi
          ;;
        *)
          if "$b" -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then
            probe_changed "$b"; return 0
          fi
          ;;
      esac
    fi
  done

  # 3) toybox wget（Android 6+ 多有，但部分精简 ROM 未编译该子命令）
  if [ -x /system/bin/toybox ]; then
    if /system/bin/toybox wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null; then
      probe_changed "toybox wget"; return 0
    fi
  fi

  # 4) ★ 万能兜底：内核 TCP 表里端口是否 LISTEN。
  #    不验证 HTTP 应答（极端情况下会误判为「活着」），但能彻底避免
  #    「工具全缺失 → 探活恒死 → 无限拉起」这种灾难性误判。
  if _port_listening; then probe_changed "/proc/net/tcp"; return 0; fi

  # 5) 最后再试 /dev/tcp（仅 bash/ksh 支持，mksh 上会直接失败，无害）
  (echo > /dev/tcp/127.0.0.1/$PORT) >/dev/null 2>&1 && { probe_changed "/dev/tcp"; return 0; }

  return 1
}

# 打印本次判定所用的通道名（App 自检与脚本共用同一套逻辑，避免"App 说不可用、脚本却活着"）
probe_channel() {
  if alive; then echo "$PROBE_CH"; else echo FAIL; fi
}

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

# ---------------------------------------------------------- 状态
# 1) App 私有目录标记文件（实时真值，有无 root 都能读）
# 2) root 域状态文件（重启/清数据后仍存在的持久真值）
# 3) 都没有 → 默认 on
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

# ---------------------------------------------------------- 拉起
wait_boot() {
  i=0
  while [ $i -lt 60 ]; do
    if service check activity 2>/dev/null | grep -q found; then return 0; fi
    sleep 2; i=$((i+1))
  done
  return 1
}

kick() {
  [ -n "$1" ] && log "拉起原因：$1"
  # 记下 am 的原始输出：被系统拒绝时（如「Background start not allowed」）
  # 光看「拉起后仍未响应」根本查不出原因。
  out=$(am start-foreground-service -n "$SVC" 2>&1)
  case "$out" in
    *"Starting service"*|*"started"*) : ;;
    *) log "am 输出：$(echo "$out" | tr '\n' ' ' | cut -c1-200)" ;;
  esac

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

# ---------------------------------------------------------- probe 模式
# 用法：sh tyd_keepalive.sh probe
# 只探一次，输出所用通道名或 FAIL 后退出。App 自检调用它，
# 保证界面上的「探活通道」与守护内部用的是同一套判断。
if [ "$1" = "probe" ]; then
  probe_channel
  exit 0
fi

# ---------------------------------------------------------- 主循环
log "=== 守护启动 v$VER (pid $$) 状态=$( [ -f "$STATE" ] && cat "$STATE" || echo 未记录 ) 端口=$PORT ==="
wait_boot
# 启动时把探活可用通道记一行，排障最有用的一条信息
if alive; then :; fi
[ -n "$PROBE_CH" ] && log "初始探活通道：$PROBE_CH" || log "警告：内置探活通道全部不可用，将依赖 /proc/net/tcp 兜底"

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

  if ! wanted; then
    [ "$IDLE_LOGGED" -eq 0 ] && log "网关已由用户主动停止，守护待命" && IDLE_LOGGED=1
    FAIL=0
    sleep "$INTERVAL"
    continue
  fi
  IDLE_LOGGED=0

  if ! app_running; then
    log "应用进程不存在，网关必然不通 -> 立即拉起"
    kick "进程缺失"
    FAIL=0
    sleep "$INTERVAL"
    continue
  fi

  FAIL=$((FAIL+1))
  if [ "$FAIL" -ge 2 ]; then
    log "进程在但探活连续失败 ${FAIL} 次 -> 拉起服务"
    kick "引擎无响应"
    FAIL=0
  fi
  sleep "$INTERVAL"
done
