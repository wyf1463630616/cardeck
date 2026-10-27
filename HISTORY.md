# 修改历史

> 约定：每次代码修改后在本文件**顶部**追加一条记录，含版本号、修改时间、修改内容简介、
> 涉及文件、可能影响的功能和模块。最新记录在最上。

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
