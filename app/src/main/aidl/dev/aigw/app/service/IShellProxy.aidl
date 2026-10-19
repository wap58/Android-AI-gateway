// Shizuku UserService 的 exec 通道。
// Shizuku 的 `newProcess` 已在 API 13 废弃、API 14 移除，
// 官方推荐的替代方式就是让 UserService 承载一个进程代理，由它来 exec。
package dev.aigw.app.service;

interface IShellProxy {
    /** 以 adb shell 身份执行命令，返回完整输出（stdout+stderr 合并）。 */
    String exec(String command);
}
