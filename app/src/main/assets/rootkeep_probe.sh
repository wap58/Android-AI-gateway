#!/system/bin/sh
# 天元鼎 · Root 守护自检探针（由 APP 以 su 身份执行，见 service/RootKeeper.kt）
#
# 为什么做成独立脚本：
#   自检要在 3 秒一次的轮询里回读好几项状态。如果每项都单独起一个 `su 0 -c`，
#   一轮就是 7 个 su 进程 —— 加密屏锁时 su 授权还会排队，界面直接卡顿、电量也顶不住。
#   这里把全部检查压进**一次** su 调用，输出 KEY=VALUE 逐行文本，App 侧一次解析。
#
# 用独立文件而不是内联长命令：内联要穿过 `su -c` 的一道 shell 解析，
# 多层引号极易失效（历史问题：su 下手动跑正常、App 里拿到空输出）。
#
# 用法：sh rootkeep_probe.sh <守护脚本路径>
# 输出（缺失项会输出空值，不报错）：
#   PID=<守护进程号>
#   FLAVOR=<Magisk|KernelSU/Sukisu|APatch|纯 su|未知>
#   STATE=<on|off|>
#   VER=<磁盘上生效的脚本版本>
#   PROBE=<探活通道名|FAIL>
#   DIR=<已铺设脚本的开机目录，逗号分隔>

SCRIPT="$1"
NAME="tyd_keepalive.sh"
PORT=8877

# ---- 守护进程（精确匹配脚本名，避免误杀/误认别的 sh）
# 锚定行尾：本脚本稍后会以 `sh tyd_keepalive.sh probe` 方式调用守护脚本，
# 不锚定的话那个一次性进程会被误判成守护进程。
PID=$(pgrep -f "$NAME\$" 2>/dev/null | head -n 1)
echo "PID=$PID"

# ---- Root 方案
# 判据要宽：不同版本/不同分支的目录布局不完全一样，
# 只看 /data/adb/ap/bin 会在部分 APatch 上认不出来（表现为「Root 方案：未知」）。
if [ -f /data/adb/magisk/magisk ] || [ -d /data/adb/magisk ]; then
  if [ -d /data/adb/ksu ] && [ ! -d /data/adb/magisk ]; then
    echo "FLAVOR=KernelSU/Sukisu"
  elif [ -f /data/adb/apd ] || [ -d /data/adb/ap ]; then
    # APatch 也可能同时带 magisk 兼容目录，用 apd/ap 优先判定
    echo "FLAVOR=APatch"
  else
    echo "FLAVOR=Magisk"
  fi
elif [ -d /data/adb/ksu ]; then
  echo "FLAVOR=KernelSU/Sukisu"
elif [ -f /data/adb/apd ] || [ -d /data/adb/ap ]; then
  echo "FLAVOR=APatch"
elif [ -d /data/adb/modules ]; then
  # 有 modules 目录但没识别出具体框架：至少说明是模块化 root
  echo "FLAVOR=Magisk 系"
else
  echo "FLAVOR=纯 su"
fi

# ---- 期望状态（root 域持久文件）
echo "STATE=$(cat /data/adb/tyd/state 2>/dev/null | tr -d ' \r\n')"

# ---- 磁盘上脚本的版本
# 版本取「真身」/data/adb/tyd/tyd_keepalive.sh —— 它才是真正在跑的那份
echo "VER=$(grep -m1 '^VER=' /data/adb/tyd/tyd_keepalive.sh 2>/dev/null | sed 's/^VER=//; s/"//g')"

# ---- 探活：优先用 /data/adb/tyd 里的真身（它一定可读），没装过才用传入的路径
if [ -f /data/adb/tyd/tyd_keepalive.sh ]; then
  SCRIPT=/data/adb/tyd/tyd_keepalive.sh
fi
# 探活本身可能耗十几秒（curl 每条预算 3 秒，网关没起来时逐级试错），
# 而本探针会被 App 每 3 秒调用一次。嵌套调用超时会拖住整个自检，
# 这里用 toybox timeout（有就用）给探活兜个上限，避免拖死轮询。
if [ -n "$SCRIPT" ] && [ -f "$SCRIPT" ]; then
  if [ -x /system/bin/timeout ]; then
    echo "PROBE=$(/system/bin/timeout 12 sh "$SCRIPT" probe 2>/dev/null)"
  elif [ -x /system/bin/toybox ]; then
    echo "PROBE=$(/system/bin/toybox timeout 12 sh "$SCRIPT" probe 2>/dev/null)"
  else
    echo "PROBE=$(sh "$SCRIPT" probe 2>/dev/null)"
  fi
else
  echo "PROBE=FAIL"
fi

# ---- 已铺设的开机转发目录（完整路径；界面自己截短）
# 正常只会有一个（脚本按当前 root 方案精确铺设）。历史残留的副本也一并报出来，
# 让界面能看见「该清理了」，而不是悄悄忽略。
DIRS=""
for d in /data/adb/service.d /data/adb/ksu/service.d /data/adb/ap/service.d /data/adb/post-fs-data.d; do
  if [ -f "$d/$NAME" ]; then
    if [ -z "$DIRS" ]; then DIRS="$d"; else DIRS="$DIRS,$d"; fi
  fi
done
echo "DIR=$DIRS"

# ---- 工作目录内容（/data/adb/tyd：真身、探针、状态、日志都在这一个目录里）
echo "WORKDIR=$(ls -1 /data/adb/tyd 2>/dev/null | tr '\n' ' ')"

# ---- 守护实例数：>1 说明单实例锁没生效，正常应恒为 1（0 表示没在跑）
# 只数真正的守护（cmdline 以脚本名结尾），排除 `sh ... probe` 这种一次性进程
echo "INSTANCES=$(pgrep -f 'tyd_keepalive.sh\$' 2>/dev/null | wc -l | tr -d ' ')"

exit 0
