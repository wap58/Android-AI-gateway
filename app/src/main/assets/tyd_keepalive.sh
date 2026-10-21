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
#    三条全挂 → 探活恒为「死」→ 每轮都拉起一次，界面显示「拉起了但仍无响应」。
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
VER="2026-10-22.9"
PORT=8877
# 探活间隔（秒）。60 秒足够：这层守护只是兜底，网关平时由前台服务/系统保活。
# 拉太密只是白白耗电 —— 每轮要起 curl + 读 /proc，一分钟一次已经能接受。
INTERVAL=60
# 分段等待粒度：把一次 INTERVAL 切成若干小段，
# 每段结束都检查「是否该退出」+「是否恢复」。这样最坏情况下卸载也只等 TICK 秒，
# 而不是等满一整个 INTERVAL。
TICK=2
BOOT_WAIT=20
BASE_DIR=/data/adb/tyd
LOG=/data/adb/tyd/keepalive.log
MAXLOG=256000

STATE=/data/adb/tyd/state
FLAG_DIR=/data/user/0/dev.tyd.app/files
FLAG="$FLAG_DIR/gateway_on.flag"

# 静默标志：probe 模式（App 自检，3 秒一次）不能写日志
QUIET=0

# 分段等待：期间持续检查「该不该退出」，让卸载/停用能秒级响应。
# 返回 0 = 正常等满；1 = 收到退出信号（调用方应立刻结束主循环）。
tick_sleep() {
  _left=$1
  while [ "$_left" -gt 0 ]; do
    # 卸载/停用标记：脚本被删或 disabled 文件出现就立刻退出
    if [ ! -f "$0" ] || [ -f "$BASE_DIR/disabled" ]; then
      return 1
    fi
    if [ "$_left" -gt "$TICK" ]; then sleep "$TICK"; _left=$((_left - TICK));
    else sleep "$_left"; _left=0; fi
  done
  return 0
}

log() {
  [ "$QUIET" -eq 1 ] && return 0
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
  if [ -f "$LOG" ]; then
    sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    # ★ 必须原地截断，不能 mv 换文件：守护进程持有的是原 inode 的写句柄，
    #   mv 之后它继续往「已 unlink 的文件」里写，日志从此停更、空间也不释放。
    if [ "$sz" -gt "$MAXLOG" ]; then
      tail -c 64000 "$LOG" > "$LOG.keep" 2>/dev/null && cat "$LOG.keep" > "$LOG" && rm -f "$LOG.keep"
    fi
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

# 记录最近一次成功的探活通道：
#   - 供排障（只在新通道生效时写一行日志）
#   - ★ 下一次探活优先用它，避免每次都从 curl 开始逐个试错
#     （curl 存在但网关已死时，每个候选都要等满 3 秒超时，一轮能拖到十几秒）
PROBE_CH=""
probe_changed() {
  [ "$1" = "$PROBE_CH" ] && return
  PROBE_CH="$1"
  # probe 模式（App 自检）每 3 秒调一次，不能往日志里写，否则日志分钟级就涨满
  [ "$QUIET" -eq 1 ] || log "探活通道：$1"
}

# 按通道名直接试一次（通道缓存命中时用，避免重复逐级回退）
try_channel() {
  case "$1" in
    *busybox*wget) [ -x "${1%% *}" ] && "${1%% *}" wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null ;;
    *toybox*wget) [ -x /system/bin/toybox ] && /system/bin/toybox wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/healthz" 2>/dev/null ;;
    /proc/net/tcp*) _port_listening ;;
    /dev/tcp) (echo > /dev/tcp/127.0.0.1/$PORT) >/dev/null 2>&1 ;;
    curl*)
      # "curl(PATH)" 是日志里的展示名，实际命令就是 PATH 里的 curl
      case "$1" in
        "curl(PATH)") command -v curl >/dev/null 2>&1 && _curl_ok curl ;;
        *) [ -x "$1" ] && _curl_ok "$1" ;;
      esac
      ;;
    *) return 1 ;;
  esac
}

alive() {
  # 0) 上次成功的通道直接再试一次：命中就返回，省掉逐级试错的时间
  if [ -n "$PROBE_CH" ]; then
    if try_channel "$PROBE_CH"; then return 0; fi
  fi

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
  # 只在应用进程存在时才采信端口兜底：
  # 否则别的 App 恰好占了同一端口时会把「网关已死」判成「活着」，永远不拉起。
  if _port_listening && app_running; then probe_changed "/proc/net/tcp"; return 0; fi

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
  # flag 存在 = 明确开着，直接采信（这是 App 的实时真值）
  if [ -f "$FLAG" ]; then return 0; fi
  # flag 不存在有两种可能：用户点了停止（App 会删）或 App 数据被清空/重装。
  # 这两种不能混为一谈 —— 前者要停手，后者应该按持久状态恢复，
  # 所以只要 STATE 文件存在，就以它为最终裁决。
  if [ -f "$STATE" ]; then
    [ "$(cat "$STATE" 2>/dev/null | tr -d ' \r\n' | cut -d' ' -f1)" = "off" ] && return 1
    return 0
  fi
  # 两个来源都没有：全新环境，默认 on（守护都装了，显然是想保活）
  return 0
}

set_state() {
  echo "$1" > "$STATE" 2>/dev/null
  chmod 644 "$STATE" 2>/dev/null
}

# ---------------------------------------------------------- 拉起
# 等系统服务就绪（开机早期 am/pm 还没起来）。
# ★ 这段等待同样要能被「卸载/停用标记」打断，否则用户在想：怎么点了卸载没反应。
#   实测过：不带检查时，卸载后进程在 wait_boot 里最长能耗满 120 秒。
wait_boot() {
  i=0
  while [ $i -lt 60 ]; do
    if service check activity 2>/dev/null | grep -q found; then return 0; fi
    if [ ! -f "$0" ] || [ -f "$BASE_DIR/disabled" ]; then return 2; fi
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
    *)
      log "am 输出：$(echo "$out" | tr '\n' ' ' | cut -c1-200)"
      # Android 12+ 从后台起前台服务会被拦（部分 ROM 连 su 身份也拦）。
      # 此时退一步用普通 start-service：GatewayService 里会尽力 startForeground，
      # 拿不到前台身份也照样 engine.start()，端口一样能活。
      case "$out" in
        *"Background start not allowed"*|*"ForegroundServiceStartNotAllowed"*|*"not allowed to start"*)
          log "前台服务被系统拦截，降级为普通服务启动"
          am start-service -n "$SVC" >/dev/null 2>&1
          ;;
      esac
      ;;
  esac

  i=0
  while [ $i -lt "$BOOT_WAIT" ]; do
    sleep 1
    # 用户在这期间点了「停止」就立刻放弃：否则我们一边等一边把它又拉起来，
    # 用户看到的是「关了又自己开」，最长能拖 40 秒。
    if ! wanted; then
      log "等待期间用户已停止，放弃本次拉起"
      # 把刚起来的服务也撤掉，避免留下一个不该在的网关
      am stop-service -n "$SVC" >/dev/null 2>&1
      return 1
    fi
    if alive; then
      log "已恢复（等待 ${i}s）"
      return 0
    fi
    i=$((i+1))
  done
  if ! wanted; then log "用户已停止，放弃第二轮"; return 1; fi
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

# ---------------------------------------------------------- 端口
# state 文件格式："on 8877" / "off"，第二段是网关端口（用户可在 App 里改）。
# 读不到就沿用脚本里的默认值。
# state 格式："on <端口> <开机恢复 0|1>" / "off"
AUTOSTART=1
if [ -f "$STATE" ]; then
  _p=$(cat "$STATE" 2>/dev/null | tr -d '\r' | cut -d' ' -f2)
  case "$_p" in
    ''|*[!0-9]*) : ;;
    *) PORT="$_p" ;;
  esac
  _a=$(cat "$STATE" 2>/dev/null | tr -d '\r' | cut -d' ' -f3)
  [ "$_a" = "0" ] && AUTOSTART=0
  unset _p _a
fi
PORT_HEX=$(printf '%04X' "$PORT")

# ---------------------------------------------------------- probe 模式
# 用法：sh tyd_keepalive.sh probe
# 只探一次，输出所用通道名或 FAIL 后退出。App 自检调用它，
# 保证界面上的「探活通道」与守护内部用的是同一套判断。
if [ "$1" = "probe" ]; then
  QUIET=1
  probe_channel
  exit 0
fi

# ---------------------------------------------------------- 单实例锁
# ★ 必须在这里自己加锁，不能只靠 shim 里的 pgrep：
#   Magisk / KernelSU / APatch 的开机目录几乎同时执行，各自 pgrep 时
#   前一个还处在 setsid 启动的窗口里，于是全部通过检查 —— 一次开机起好几个守护，
#   互相抢着 am start-foreground-service（日志里连续三行「守护启动」就是这个）。
#   mkdir 是原子的，谁先建目录谁留下，其余立刻退出。
mkdir -p /data/adb/tyd 2>/dev/null
LOCK_DIR=/data/adb/tyd/daemon.lock
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  _old=$(cat "$LOCK_DIR/pid" 2>/dev/null)
  if [ -n "$_old" ] && kill -0 "$_old" 2>/dev/null; then
    # 已有存活实例，安静退出（不写日志，避免每次开机刷一行）
    exit 0
  fi
  # 锁是残留的（进程已死 / 上次被 kill -9）：清掉重抢
  rm -rf "$LOCK_DIR" 2>/dev/null
  mkdir "$LOCK_DIR" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK_DIR/pid"
# 无论怎么退出都释放锁，否则下一次起来会被自己的残留锁挡住
# 退出时释放锁。
# ★ trap 里必须显式 exit：只写 `rm -rf` 的话，收到 TERM/INT 后进程只是删了个锁，
#   主循环继续转 —— 表现就是「kill 了还在跑」，卸载时删完目录又被它踩回来。
#   （实测确认过：不加 exit 时 terminate 后 5 秒进程仍然存活。）
trap 'rm -rf "$LOCK_DIR" 2>/dev/null; exit 0' INT TERM HUP
trap 'rm -rf "$LOCK_DIR" 2>/dev/null' EXIT

# ---------------------------------------------------------- 主循环
log "=== 守护启动 v$VER (pid $$) 状态=$( [ -f "$STATE" ] && cat "$STATE" || echo 未记录 ) 端口=$PORT ==="
wait_boot
_wb=$?
if [ "$_wb" -eq 2 ]; then
  log "等待系统服务期间收到卸载/停用标记，守护退出"
  rm -rf "$LOCK_DIR" 2>/dev/null
  exit 0
fi
# 启动时把探活可用通道记一行，排障最有用的一条信息
if alive; then :; fi
[ -n "$PROBE_CH" ] && log "初始探活通道：$PROBE_CH" || log "警告：内置探活通道全部不可用，将依赖 /proc/net/tcp 兜底"

if [ ! -f "$STATE" ]; then
  if wanted; then set_state on; else set_state off; fi
  log "初始化状态文件：$(cat "$STATE" 2>/dev/null)"
fi

FAIL=0
IDLE_LOGGED=0
# 用户关掉了「开机自启」时：只在本次开机里「见过网关活着」之后才负责复活。
# 这样语义才对 —— 关掉的是「开机自动开」，不是「被杀后不复活」。
SEEN_ALIVE=0
while true; do
  if alive; then
    FAIL=0
    IDLE_LOGGED=0
    SEEN_ALIVE=1
    tick_sleep "$INTERVAL" || { log "收到卸载/停用标记，守护退出"; exit 0; }
    continue
  fi

  if ! pkg_installed; then
    [ "$IDLE_LOGGED" -eq 0 ] && log "包未安装，等待" && IDLE_LOGGED=1
    tick_sleep "$INTERVAL" || exit 0
    continue
  fi

  if ! wanted; then
    [ "$IDLE_LOGGED" -eq 0 ] && log "网关已由用户主动停止，守护待命" && IDLE_LOGGED=1
    FAIL=0
    tick_sleep "$INTERVAL" || exit 0
    continue
  fi
  IDLE_LOGGED=0

  # 关掉了开机自启、且本次开机还没见它活过 -> 开机阶段不主动拉起
  if [ "$AUTOSTART" -eq 0 ] && [ "$SEEN_ALIVE" -eq 0 ]; then
    [ "$IDLE_LOGGED" -eq 0 ] && log "开机自启已关闭，本次开机未见网关运行，守护待命" && IDLE_LOGGED=1
    tick_sleep "$INTERVAL" || exit 0
    continue
  fi

  if ! app_running; then
    log "应用进程不存在，网关必然不通 -> 立即拉起"
    kick "进程缺失"
    FAIL=0
    tick_sleep "$INTERVAL" || exit 0
    continue
  fi

  FAIL=$((FAIL+1))
  if [ "$FAIL" -ge 2 ]; then
    log "进程在但探活连续失败 ${FAIL} 次 -> 拉起服务"
    kick "引擎无响应"
    FAIL=0
  fi
  tick_sleep "$INTERVAL" || exit 0
done
