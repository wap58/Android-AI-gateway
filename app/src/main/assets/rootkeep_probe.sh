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

# ---- Root 方案（按框架自己的标志文件判定，不看目录是否存在）
if [ -f /data/adb/magisk/magisk ]; then
  echo "FLAVOR=Magisk"
elif [ -d /data/adb/ksu/bin ]; then
  echo "FLAVOR=KernelSU/Sukisu"
elif [ -d /data/adb/ap/bin ]; then
  echo "FLAVOR=APatch"
else
  echo "FLAVOR=纯 su"
fi

# ---- 期望状态（root 域持久文件）
echo "STATE=$(cat /data/adb/tyd_keepalive.state 2>/dev/null | tr -d ' \r\n')"

# ---- 磁盘上脚本的版本
echo "VER=$(grep -m1 '^VER=' "$SCRIPT" 2>/dev/null | sed 's/^VER=//; s/"//g')"

# ---- 探活：直接复用守护脚本自己的判定，保证两边结论一致
if [ -n "$SCRIPT" ] && [ -f "$SCRIPT" ]; then
  echo "PROBE=$(sh "$SCRIPT" probe 2>/dev/null)"
else
  echo "PROBE=FAIL"
fi

# ---- 已铺设的开机目录
DIRS=""
for d in /data/adb/service.d /data/adb/ksu/service.d /data/adb/ap/service.d; do
  if [ -f "$d/$NAME" ]; then
    if [ -z "$DIRS" ]; then DIRS="$d"; else DIRS="$DIRS,$d"; fi
  fi
done
echo "DIR=$DIRS"

exit 0
