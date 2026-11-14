# 修改历史

> 约定：每次代码修改后在本文件**顶部**追加一条记录，含版本号、修改时间、修改内容简介、
> 涉及文件、可能影响的功能和模块。最新记录在最上。

---

## v0.1.13+teardown.1 · 2026-11-01 15:45 · 分支 backconnect

### 修改内容简介

修复「断联后无法同进程重连、必须退出车机 CarPlay / 重启 App 才恢复」的会话生命周期缺陷。
根因来自 2026-10-31 两份真车日志（`172146连接成功` / `172332连接不成功`）的对照分析：
协议实现本身无问题，**收尾缺失**导致车机侧残留会话状态。

- **根因（日志定谳）**：全仓此前**没有任何 TEARDOWN / 会话结束消息**，收尾只是关 socket。
  车机的 CarPlay 状态机收不到结束信号，下一轮 iAP2 回连后表现为「链路层 ACK 我们的
  0x1d00、CSM 层再不回 0x1d01」，35s 后由对端拆链，再往后连链路层 0x80 都不应答。
  同一份日志里两次成功建链都发生在「此前无 AirPlay 会话」的空闲态，机制吻合。
- **P0-1 协议级收尾**：新增 `AirPlayClient.teardown()`（RTSP TEARDOWN，1.5s 上限、
  幂等、失败只留痕不抛），由 `SessionController.closeCurrentClient()` 统一在两类断联
  触发——**被动断联**（`VideoSender.onEnded`：推流终止/端口断开/车机掉线）与
  **主动断开/退役**（`retireSession`）。被动断联是关键：此刻控制通道通常还活着，
  是唯一能体面收尾的时机，错过就只能等车机自己超时（实测 35~45s），期间任何重连都被拒答。
- **P0-1 附带**：`close()` 现在一并关闭事件通道 socket（此前一直活到被对端 reset），
  避免车机侧「还有发送端挂在我的事件端口上」判断不解除。
- **P0-2 快速判死**：0x1d01 从「吃满整体 60s deadline」改为独立 6s 窗口
  （`IDENTIFICATION_WAIT_NANOS`；实测正常 31~62ms 到达），并把失败原因写清：
  链路 ACK 了请求但不回身份 = 车机侧仍停留在上一轮会话。快速失败交给上层重连，
  不再让用户面对 35s 无反馈。
- **诊断埋点**：TEARDOWN 应答码 / 跳过原因 / 未完成原因逐条留痕；0x1d01 超时时打印
  已等待时长、暂存帧数、链路存活状态；iAP2 主动拨号超时后补一行
  「RFCOMM 已建立但 iAP2 无应答（本车机不接受手机发起）」——与「连不上」明确区分。
- **日志文案纠偏**（两处会把排查引偏）：
  1. `MainActivity` 的「尚未拿到热点密码（需蓝牙会话 0x5703）」→ 本车机不支持 0x5703，
     AP 信息只走 0x4301，改为「需先完成蓝牙会话：0x1d01 识别 → 0x4301 自带 AP 信息」；
  2. `Iap2BluetoothClient` 的「车机在『蓝牙已连接』状态下不接受 CarPlay」是奥迪场景的
     推断，却被打印成通用结论；改为事实陈述（已断开哪些配置文件）。
- **未做（有意）**：`/ctrl-int/1/disconnect` 未实现。该通道方向是**接收端 → 发送端**
  （日志中的 `GET /ctrl-int/1/connect` 正是车机发给我们），本端没有对车机的 ctrl-int
  端点，盲发该请求无协议依据。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `airplay/AirPlayClient.kt` | 新增 `sessionRecorded`/`teardownAttempted`/`teardown()`；`record()` 置位；`ensureConnected` 复位；`close()` 关闭事件通道 |
| `SessionController.kt` | 新增 `closeCurrentClient(reason)`；`retireSession` 与 `VideoSender.onEnded` 接入 |
| `iap2/Iap2SenderSession.kt` | 新增 `IDENTIFICATION_WAIT_NANOS` + `expectIdentification()`（6s 窗口 + 诊断） |
| `bt/Iap2BluetoothClient.kt` | 会话失败后区分「RFCOMM 已建立但无 iAP2 应答」；profile 断开日志改为事实陈述 |
| `MainActivity.kt` | 热点密码缺失文案纠偏 |

### 可能影响的功能和模块

- **会话收尾**：所有结束路径多一次 RTSP TEARDOWN（≤1.5s，后台线程执行，不阻塞 UI）。
  接收端不支持该消息时仅留痕，行为退化为改造前（关 socket），无功能损失。
- **断联重连**：目标正是让被动断联后**无需重启**即可在同一进程内重连——车机侧收到
  收尾后下一轮 iAP2 能正常给出 0x1d01。若某车机对 TEARDOWN 无响应，最坏等于现状。
- **事件通道**：`close()` 新增关闭动作；`postEventCommand` 在通道关闭后返回 false
  （原逻辑已 catch IOException）。连续发送到已关闭通道的场景仅发生在会话终止后。
- **识别阶段**：0x1d01 窗口 60s→6s。极慢车机（>6s 才回身份）会被判失败并重连，
  实测样本（CAMRY/DiPlay/奥迪）均在 62ms 内到达，风险可接受；如需放宽只改一个常量。
- **未验证项**：真车 TP 层面的 TEARDOWN 应答（CAMRY 是否回 200）；断联→重连的完整
  闭环需装机复现（步骤见下）。

### 验证建议

1. 连接成功 → 车机上「退出 CarPlay」或关闭车机热点 → 观察日志出现
   `AIRPLAY: TEARDOWN 应答 [...]` 或 `TEARDOWN 跳过——控制通道已关闭`；
2. 3 秒内重连 → 预期 0x1d01 在 100ms 内到达并正常推流（不再需要重启 App）；
3. 对照旧包：同一操作序列下旧包应复现「0x1d00 被 ACK 后静默 35s」。

---

## v0.1.13+backconnect.5 · 2026-10-29 15:15 · 分支 backconnect

### 修改内容简介

修复「自动回连」配置的**双源状态漂移**（代码审查发现）：持久化偏好是唯一真值源，
进程内静态字段改为只读镜像，统一经 `syncWaitCarFromPrefs` 恢复。

- **问题**：`Iap2BluetoothClient.waitCarReconnect` 为静态 `@Volatile`，仅在
  `MainActivity.setUpUiControls()` 同步一次。进程被系统回收、前台服务存活而
  Activity 未重建时，静态字段回落默认 `false`，用户选定的「等待车机」被**静默降级**
  为 3s 重连。
- **修复**：
  1. `waitCarReconnect` 改为 `private set`（禁止外部直写，防漂移复发）；
  2. 新增 `syncWaitCarFromPrefs(context)` 作为**唯一同步入口**（读 preferences
     `settings.autoReconnectWaitCar`，异常兜底 false）；
  3. `start()` 内每轮启动调用一次——连接建立前必然对齐真值；
  4. `MainActivity.apply()` 改为「先落盘 → 再调同步入口」，UI 与运行时永远一致。
- **文案**：描述行补「（下一轮生效）」，说明运行中切模式不影响已进入的退避周期。
- **等价性保持**：`waitCarReconnect=false`（手机发起）路径仍严格等价改造前——
  3s 重连、无新增日志、无额外分支。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `bt/Iap2BluetoothClient.kt` | 偏好键常量、`private set`、`syncWaitCarFromPrefs`、`start()` 调用 |
| `MainActivity.kt` | `apply()` 落盘后走统一同步入口；描述文案补生效时机 |

### 可能影响的功能和模块

- **「手机发起」模式**：行为零变化（3s 重连、日志、分支均与改造前一致）。
- **「等待车机」模式**：跨进程重建后不再丢失用户选择。
- **蓝牙连接启动路径**：`start()` 增加一次 SharedPreferences 读取（内存缓存，微秒级，
  在主线程外调用；`start()` 由 `SessionController.startConnection` 在 UI 线程调用但
  SharedPreferences 读取为内存操作，可接受）。
- 待真机验证：切换模式后杀进程重进，设置是否保持。

---

## v0.1.13+backconnect.4 · 2026-10-28 20:05 · 分支 backconnect

### 修改内容简介

「自动回连」行布局与连接准备其它行统一：SeekBar+值文本 → **分段双态按钮组**
（「手机发起 | 等待车机」，bg_segment/bg_segment_sel，与 30/60fps 同款交互）。

- 布局：activity_main.xml 自动回连行右侧换 segment 双按钮（紧凑 12dp 内边距）。
- 逻辑：MainActivity 绑定改双按钮点击（选中蓝片白字/未选透明次字）；
  配置语义与持久化不变（autoReconnectWaitCar，默认手机发起）。

### 涉及文件

`res/layout/activity_main.xml`、`MainActivity.kt`

### 可能影响的功能和模块

- 仅该行控件形态变化；行为零变化。待真机验证布局对齐效果。

---

## v0.1.13+backconnect.3 · 2026-10-28 19:50 · 分支 backconnect

### 修改内容简介

miuix 风格改**深色**（用户反馈推翻上一轮浅色方案），且控件元素（开关/滑块）miuix 化。

- **色板反转**：colors.xml m3_* 换深色 HyperOS 令牌——页面 #0D0D0D / 卡片 #1C1C1E /
  主字 #E6FFFFFF / 次字 #99FFFFFF / 强调亮蓝 #5A8DFF / 成功 #2ED573 / 警告 #FFC44D /
  错误 #FFFF6B6B（深色下浅红），8 位 hex。
- **控件 miuix 化**（上一轮只换了色，本轮做元素）：
  - 开关（sw_track/sw_thumb ×2态）：miuix 白圆滑块 + 蓝/灰胶囊轨道
    （弃用旧「深底白勾」M3 样式）；
  - 滑块 ×6（autoReconnect/displayScale/throttle/dockScrim/dockBlur/dockWidth）：
    挂 miuix 轨道（灰底 6dp 圆角 + 蓝进度）+ 白圆滑块头（bg_seek_track/thumb 新建）；
  - 复选框/单选/徽章/分段/CTA：drawable 深色 miuix 化（蓝 #5A8DFF 选中系）。
- **布局/Kotlin 硬编码色**：浅色值全部反转为深色系（16 布局 + 4 Kotlin 文件）。
- **暗幕联动简化**：深色主题下系统栏恒深（常态 #0D0D0D / 暗幕纯黑），light bars 恒关
  （防恢复路径误亮）；黑遮罩与深色系统栏天然融合。
- **边界不变**：车机 launcher 运行时保持深色车载观感；hudmock 分支 HUD 测试页待处理。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `values/colors.xml` / `themes.xml` | 深色 miuix 令牌 + 深色主题 |
| `drawable/sw_thumb_on/off` `sw_track_on/off` | 开关 miuix 化（白圆 + 蓝/灰胶囊） |
| `drawable/bg_seek_track/thumb` | **新建**：miuix 滑块轨道/滑块头 |
| `drawable/` ×28（bg_*） | 深色 miuix 反转 |
| `layout/` ×16 + 6 处 SeekBar 挂样式 | 硬编码色反转 + 控件样式挂载 |
| 4 个 Kotlin 文件 | 硬编码色反转 + 暗幕联动简化 |

### 可能影响的功能和模块

- **视觉**：整体深色 HyperOS 观感；开关/滑块形态变化（白圆滑块）。
- **假息屏**：系统栏恒深，联动仅纯黑微调，白边风险消除。
- **待真机验证**：开关/滑块触摸手感与视觉、深色弱字对比度、车机侧设置面板观感。

---

## v0.1.13+backconnect.2 · 2026-10-27 17:50 · 分支 backconnect

### 修改内容简介

全 app UI 切换 miuix 浅色设计语言（HyperOS，用户指定「全 app + 浅色」）。

- **色板**：colors.xml 全量换浅色 miuix 令牌（色名沿用 m3_*，布局引用零改动）——
  页面 #F7F8FA / 卡片白 / 主字 #E6000000 / 次字 #99000000 / 强调 HyperOS 蓝 #3482FF
  / 成功 #00B578 / 错误 #D0342C，统一 8 位 hex（含 alpha）。日志控制台保留深色块
  （miuix 代码块风格）。
- **主题**：Theme.CarPlaySender 浅色系统栏（light bars）+ 浅背景。
  **假息屏兼容**：ScreenDimmer 新增 onDimChanged 回调，暗幕显隐时 MainActivity
  动态把系统栏切黑/恢复（防 2026-10-18「暗幕露白边」问题回归）。
- **drawable**：30 个 bg_* 全部换浅色 miuix（白卡 16dp 圆角、浅灰分段/磁贴、
  蓝选中片、浅 tint 徽章、胶囊 CTA 蓝底白字、灰涟漪）。
- **布局**：activity_main、activity_settings(_content)、activity_phone_settings、
  fragment_panel_*(desktop/supersample/orientation/dock/map/display)、
  item_*(app_pick/app_ss/app_cell/map_option/orientation_car/section_header)
  硬编码色全部替换（iOS 深色系 → miuix 浅色系）。
- **代码硬编码色**：MainActivity（hubCaption 蓝）、SettingsActivity（菜单字）、
  SettingsFragments/SettingsRows（提示/错误/未选字）。
- **范围边界**：车机 launcher 运行时（GridActivity/DockActivity/dock_bar/dock_sidebar/
  view_dashboard/LetterboxHint/DashSplitHandle）保持深色车载观感不动。
- **未含**：hudmock 分支的 HUD 测试页（fragment_panel_hud_mock.xml）——该文件不在
  本分支，待 hudmock 分支单独套用同款浅色（色值清单同上）。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `values/colors.xml` / `values/themes.xml` | 浅色 miuix 色板 + 浅色主题/系统栏 |
| `drawable/` ×30（bg_*） | 白卡/浅灰面/蓝强调/浅 tint 徽章/胶囊按钮 |
| `layout/` ×16（activity_main/settings/phone_settings/fragment_panel_*/item_*） | 硬编码色替换 |
| `MainActivity.kt` / `launcher/SettingsActivity.kt` / `launcher/settings/SettingsFragments.kt` / `SettingsRows.kt` | 代码硬编码色 + 暗幕系统栏联动 |
| `ui/ScreenDimmer.kt` | onDimChanged 回调 |

### 可能影响的功能和模块

- **视觉全翻转**（深色→浅色）：主页/设置/弹窗/徽章/按钮全部换观感，布局结构零改动。
- **假息屏**：系统栏随暗幕切黑（新联动逻辑），行为等价原深色方案。
- **车机侧 launcher**：零改动（深色观感保持）。
- **待真机验证**：浅色对比度（弱字 #61000000 在白底）、暗幕白边防护、
  车机侧设置面板浅色观感（fragment_panel 共用）。

---

## v0.1.13+backconnect.1 · 2026-10-27 09:25 · 分支 backconnect（自 main 检出）

### 修改内容简介

新增「自动回连」配置（连接准备区第五项）：主动连失败后的重连策略可选。

- **背景**（2026-10-26/27 CAMRY 实测）：主动连入车机 RFCOMM 后 iAP2 SYN 无响应、
  协商必超时；成功路径是**失败后车机主动回连**（实测 ~2s 内接管）。原逻辑失败后
  3s 立即重连主动连，与车机回连抢同一条无线 iAP2 上下文，用户误以为「连不上」。
- **配置项**：连接准备四项之后新增第五项「自动回连」，滑块两档（SeekBar 0/1）：
  - 选项 1「手机发起」（**默认**）：失败后 3s 重试主动连——现有逻辑，零行为变化；
  - 选项 2「等待车机」：失败后暂停主动重连 25s（长退避让出回连窗口），日志提示，
    车机回连（BtSessionGate.inboundActive）自动接管会话。
- **持久化**：SharedPreferences `settings.autoReconnectWaitCar`（默认 false）；
  启动时恢复并同步到 `Iap2BluetoothClient.waitCarReconnect`。
- **不计入准备计数**（readyCount 不变）：该项是偏好配置而非前置条件。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `res/layout/activity_main.xml` | 连接准备区新增「自动回连」行（图标+标题+desc+SeekBar+值文本） |
| `MainActivity.kt` | 滑块绑定与配置持久化（setUpUiControls 连接准备区） |
| `bt/Iap2BluetoothClient.kt` | `waitCarReconnect` 标志 + `retryDelayMillis()` 重连节拍分支（3s/25s） |

### 可能影响的功能和模块

- **蓝牙连接流程**：默认「手机发起」行为与改动前完全一致；选「等待车机」后失败重连
  节拍变慢（3s → 25s），换取车机回连窗口——目标车机为 CAMRY 类「回连式」机型。
- **连接准备 UI**：新增一行配置项，不动准备计数与既有四项。
- **待真机验证**：等待车机模式下（车机回连日志「接收端已连入」）能否稳定接管；
  25s 窗口是否足够。

---

## v0.1.12+hudmock.3 · 2026-10-26 20:15 · 分支 hudmock

### 修改内容简介

真机日志诊断（CAMRY/德赛西威车机，2026-10-26）：HUD 不显示 + 「链路已死」两个症状定因。

- **HUD 不显示根因**：车机识别清单声明可收 0x5201/0x5202/0x5204（发送清单含 0x5200/0x5203），
  但**全程未发 0x5200 订阅**——协议语义是车机订阅后手机才上报 RG 更新（真 iPhone 同款）。
  未订阅 = 车机未进入 RG 消费状态，不画 HUD 是预期行为；「声明可收」≠「会订阅」。
- **「链路已死」是余波不是根因**：发送 mock 后 4s 车机拆掉整个 CarPlay 会话
  （NSD 消失 → WiFi 断 → 连接 abort），旧引擎重传 packet 110 超时判死。
  未订阅先发 0x5201/0x5202 疑似触发车机固件异常拆会话（时序嫌疑：第二对发送后 4s 拆）。
- **修复**：
  1. 订阅状态追踪（0x5200 置位 / 0x5203 与会话关闭清零）；测试页打开即显示订阅状态；
  2. 未订阅时发送带 ⚠ 警示行（不拦截——保留 mock 强发能力，风险已告知）；
  3. 死亡文案纠偏：「iAP2 链路已失效（车机拆会话/重传超时），请重新连接后再试」
     （原「协商失败/超时」误导）。
- **正确测试流程**（写入测试页提示）：先在车机 CarPlay 起导航触发 0x5200 订阅
  → 日志出现「0x5200 订阅到达」→ 再发 mock 替换真实数据看 HUD。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `airplay/RouteGuidanceSender.kt` | subscribed 状态 + describeSubscription + 未订阅警示 |
| `airplay/VirtualIap2Link.kt` | 0x5200 置订阅/0x5203 退订分支；死亡文案纠偏 |
| `airplay/AirPlayClient.kt` | close() 清 subscribed |
| `launcher/settings/HudMockPanelFragment.kt` | 打开时显示订阅状态行 |

### 可能影响的功能和模块

- **HUD 测试页**：新增订阅状态提示与未订阅警示；发送行为不变（警示不拦截）。
- **iAP2 应答**：新增 0x5203 退订处理（原先落「未处理」计数）。
- **连接/推流**：零改动。
- **待真机验证**：① 起导航后 0x5200 是否到达（决定 HUD 测试能否闭环）；
  ② 订阅后发 mock 是否触发车机拆会话（若仍拆 = 字段格式问题，需抓包修正宽度）。

---

## v0.1.12+hudmock.2 · 2026-10-24 19:55 · 分支 hudmock

### 修改内容简介

修复 HUD 测试页点「发送」报「入队失败」无从归因的问题（真机实测反馈）。

- **根因定位**：`Iap2LinkEngine.sendControl` 仅在两种情形返回 false——引擎已 DEAD
  （重传看门狗超时/对端 reset 触发 `die()`）或帧超协商上限（mock 帧几十字节，不可能超）。
  实测的「入队失败」= 引擎已死：iAP2 协商半途失败/超时后 engine 进 DEAD，或会话已关
  但发送委托仍持旧实例（死引擎拒帧）。旧版 UI 只显示笼统的「入队失败」，无法区分。
- **失败诊断细分**（`sendCsmFromUi` 返回值从 Boolean 改为 `String?` 失败原因）：
  链路已断开 / 引擎已死请重连 / 入队被拒（含 state）/ 已入队但等待链路就绪（NEGOTIATING）。
- **生命周期修复**：会话 close() 清 `RouteGuidanceSender.transmit`（原先残留旧 lambda）。
- **测试页增强**：打开即提示链路状态（有无活动链路）。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `airplay/VirtualIap2Link.kt` | `sendCsmFromUi` 返回失败原因（alive/isDead/state 三级诊断） |
| `airplay/RouteGuidanceSender.kt` | transmit 委托签名改 `(Int, ByteArray) -> String?`；文案带原因 |
| `airplay/AirPlayClient.kt` | close() 清 transmit（与 iapLink 同生命周期） |
| `launcher/settings/HudMockPanelFragment.kt` | 打开时链路状态提示 |

### 可能影响的功能和模块

- **HUD 测试页**：失败时日志给出具体原因与处置建议（重连/等待就绪）。
- **发送链路**：`sendCsmFromUi` 签名变化（返回 String?），调用点仅 RouteGuidanceSender 一处。
- **连接/推流/触摸**：零改动；close() 多清一个委托变量，无副作用。
- **仍未验证**：真机 HUD 渲染；本次仅修复诊断与生命周期，协议组帧未变。

---

## v0.1.12+hudmock.1 · 2026-10-23 19:55 · 分支 hudmock

### 修改内容简介

新增 HUD 测试功能：模拟发送 iAP2 Route Guidance 导航数据事件（0x5201/0x5202），
验证车机 HUD 是否渲染转向指令（协议依据 accessoryd 23G71 逆向，字段注释见 RouteGuidanceSender）。

- **设置菜单入口**：车机双栏与手机二级菜单共用的设置菜单列表新增第 7 项「HUD 测试」，
  点击进入 mock 测试页（`HudMockPanelFragment`）。
- **mock 测试页**：上半屏多行输入框（自定义文本）+「发送 HUD 事件」「结束导航」按钮；
  下半屏日志窗口（本页收发记录 + 链路侧事件，自动滚动）。
  发送 = 0x5201 骨架（ROUTE_SET/路名/距离/计数/可见位）→ 0x5202 槽位详情
  （默认右转 EManeuverType=2、500m，文本作路名与指令）；结束 = NO_ROUTE 收尾（HUD 灭卡）。
- **组帧器**：新增 `RouteGuidanceSender`（纯函数组帧 + 发送委托 + 事件回调），
  TLV 编码走 `Iap2Csm.param`；数值宽度保守取值（状态 u8/距离 u32/字符串 NUL 结尾），
  真机不符按抓包修正。
- **P0 空态应答**：车机 0x5200 StartRouteGuidanceUpdates 订阅不再忽略，回 0x5201
  NO_ROUTE 空态（与 0x5001 空态同款打法）；并经 `RouteGuidanceSender.onEvent`
  通知测试页日志窗口。
- **发送通道**：`VirtualIap2Link.sendCsmFromUi`（进引擎 backlog，非阻塞）；
  `AirPlayClient.obtainIapLink` 创建链路时注册 `RouteGuidanceSender.transmit`。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `iap2/Iap2MessageIds.kt` | 新增 0x5201/0x5202/0x5203 常量与命名 |
| `airplay/RouteGuidanceSender.kt` | **新建**：组帧器（mock 序列/NO_ROUTE）+ 发送委托 |
| `airplay/VirtualIap2Link.kt` | 0x5200 订阅应答分支；`sendCsmFromUi` 公开发送入口 |
| `airplay/AirPlayClient.kt` | obtainIapLink 注册 `RouteGuidanceSender.transmit` |
| `launcher/settings/SettingsMenus.kt` | HUD_MOCK 菜单项 + Fragment 注册 |
| `launcher/settings/HudMockPanelFragment.kt` | **新建**：测试页（输入/按钮/日志窗口） |
| `res/layout/fragment_panel_hud_mock.xml` | **新建**：测试页布局 |
| `test/.../airplay/RouteGuidanceSenderTest.kt` | **新建**：组帧自洽单测 7 项 |

### 可能影响的功能和模块

- **iAP2 订阅应答（行为变化）**：0x5200 到达时新增 0x5201 NO_ROUTE 应答帧（原先仅计数
  留痕）。空态不触发 HUD，理论上无副作用；若某车机对 0x5201 有异常反应，回退方式是
  去掉 `VirtualIap2Link` 中 `START_ROUTE_GUIDANCE_UPDATES` 分支。
- **设置菜单**：新增一项菜单项，不影响既有 6 项。
- **连接/推流/触摸/音频链路**：零改动（新功能全部走既有 CSM 发送通道，无链路逻辑变化）。
- **未验证项**：真机 HUD 渲染效果（需装机连奥迪 MIB2/MIB3 实测）；0x5201/0x5202
  数值字段宽度为保守取值，真机不符时按抓包修正。
