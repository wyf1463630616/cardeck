# 修改历史

> 约定：每次代码修改后在本文件**顶部**追加一条记录，含版本号、修改时间、修改内容简介、
> 涉及文件、可能影响的功能和模块。最新记录在最上。

---

## v0.1.12+shiorroot.1 · 2026-10-21 11:20 · 分支 shiorroot

### 修改内容简介

特权后端双通道改造：原先**强制 Shizuku** 的检测与功能实现改为**可选 root 实现**。

- **检测策略**：所有原本触发 Shizuku 检测的时机，先触发 root 检测（`su -c id` 实测）；
  root 成功 → 缓存结果并全程优先走 root，不再强制检测 Shizuku（不再反复弹 su 授权窗）；
  root 失败 → 回退原 Shizuku 链路，行为与改造前一致。
- **A 层（shell 命令，8 文件 40 处调用点零改动）**：`Shell.exec` 内部按后端分发——
  root 走 `su -c`（RootShell），否则走原 Shizuku newProcess。
- **B 层（触摸注入 + 受信虚拟屏 + IME 策略）**：root 走常驻 daemon——
  `su -c app_process` 拉起 `RootDaemonEntry`（uid 0 启动后 `setuid(2000)` 对齐
  Shizuku UserService 的 shell 权限画像），复用 `CarInputServiceImpl` 全部反射
  注入/受信屏逻辑，经 ServiceManager 注册 `ICarInput` 供应用侧 binder 直连（毫秒级）。
  root daemon 随宿主 App 消失自尽（watchdog 比对 appPid）。
- **C 层（检测门与 UI）**：连接强制门、状态行、引导弹窗改为 root/Shizuku 两路文案；
  状态行标注当前后端（root 就绪显示「无需 Shizuku」）。
- **引导文案**：root 未决时给出「root 授权」与「Shizuku」两条路；root 已实测不可用时只留 Shizuku 方案。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `privilege/RootGate.kt` | 新增：root 探测（`su -c id`）+ 进程内缓存（超时不写缓存，可重探） |
| `privilege/RootShell.kt` | 新增：`su -c` 命令执行（与 `Shell.exec` 同 Result 语义） |
| `privilege/PrivilegeGate.kt` | 新增：后端选择（root 优先→Shizuku 回退）+ 两路引导文案 |
| `privilege/RootDaemonEntry.kt` | 新增：root daemon 主体（app_process + setuid(2000) + ServiceManager 注册） |
| `privilege/RootDaemonClient.kt` | 新增：daemon 拉起/直连/重置（应用侧客户端） |
| `shizuku/Shizuku.kt` | `Shell.exec` 双后端分发；`CarInputClient.service/ensure/logSink` 双后端分发 |
| `MainActivity.kt` | 检测门改 `PrivilegeGate`（7 处）；UI 状态行/引导分 root/Shizuku 两路 |
| `SessionController.kt` | `startConnection`/`autoJoinCarWifi` 检测门改 `PrivilegeGate`（3 处） |
| `capture/HiddenDisplaySource.kt` | `attach` 前置检测改 `PrivilegeGate` |
| `launcher/VirtualLauncher.kt` | 受信屏等待逻辑改 `PrivilegeGate`；日志标注后端 |
| `app/src/test/.../privilege/PrivilegeGateGuidanceTest.kt` | 新增：引导文案单测（含 root 提示断言） |

### 可能影响的功能和模块

1. **触摸注入 / 虚拟屏 / IME 策略**（B 层）：root 后端走新 daemon 通道，daemon 的
   `setuid(2000)` 与 ServiceManager 注册**未经真机验证**——这是本次改动最大的风险点。
   若 daemon 拉起失败，日志会显示 `ROOTDAEMON: daemon 注册超时`，回退建议：本次会话
   仍可走 Shizuku（需 root 探测失败才会回退；root 探测成功但 daemon 失败时无自动回退，
   见下方"已知边界"）。
2. **shell 命令类功能**（A 层）：`settings`/`am`/`cmd`/`dumpsys` 全部 40 处调用点行为
   不变（仅执行通道从 Shizuku newProcess 换 `su -c`）；`Shell.exec` 的主线程告警保留。
3. **Wi-Fi 自动加入、通知使用权静默授权、屏幕超时、letterbox、compat 开关、任务栈管理**：
   依赖 A 层，逻辑零改动。
4. **Shizuku 原有链路**：root 不可用时完整保留（四态检测、引导、UserService 绑定、
   陈旧进程回收 reapStaleUserServices 均不受影响）。
5. **首次 root 探测**：触发 Magisk/KernelSU 授权弹窗（`su -c id`），需用户点「允许」；
   超时（15s）不写缓存，下次可重探（「重试检查」按钮）。

### 已知边界（后续迭代）

- root 探测成功但 daemon 注册失败（如 SELinux 拦 `addService`）时，本次进程内不会
  自动回退 Shizuku（root 探测已缓存 ROOT）；用户重启 App 后若 root 探测仍成功会重试
  daemon。如需"daemon 失败即回退 Shizuku"的混合策略，属后续增强。
- `RootDaemonEntry` 的 `setuid/setgid/prepareMainLooper` 用 deprecated API（功能完整），
  换新 API 需 minSdk 侧适配，暂保持。
