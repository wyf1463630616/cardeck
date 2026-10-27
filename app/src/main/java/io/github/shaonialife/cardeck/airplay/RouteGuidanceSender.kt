package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds

/**
 * Route Guidance（iAP2 0x5201/0x5202）组帧器——HUD 导航数据事件（mock 测试用）。
 *
 * 格式依据 accessoryd 23G71 逆向对齐（ACCNav_RGUpdate_* 字段枚举，无公开文档）：
 *  - 0x5201 RouteGuidanceUpdate：路由级状态（state/路名/距离/maneuver 计数/可见位）
 *  - 0x5202 RouteGuidanceManeuverUpdate：单 maneuver 详情（类型/指令文本/距离）
 *
 * 编码走 [Iap2Csm.param]（u16 长度 | u16 ID | 值，大端、可嵌套）。车机解析器
 * **整帧校验**：每条 TLV `len >= 4` 且不越界，残帧整条丢弃——组帧必须自洽。
 *
 * 数值宽度为保守取值（状态/枚举 u8、距离 u32 米、字符串 UTF-8 + NUL）；
 * 真机对不上时按抓包修正，未知宽度错误不会拆会话（仅字段语义失效）。
 */
object RouteGuidanceSender {
    // ---- 消息 ID（同 Iap2MessageIds，单测就近引用）----
    const val MSG_UPDATE = Iap2MessageIds.ROUTE_GUIDANCE_UPDATE // 0x5201
    const val MSG_MANEUVER = Iap2MessageIds.ROUTE_GUIDANCE_MANEUVER_UPDATE // 0x5202
    const val MSG_STOP = Iap2MessageIds.STOP_ROUTE_GUIDANCE_UPDATES // 0x5203

    // ---- 0x5201 字段 ID（ACCNav_RGUpdate_* 对齐）----
    const val F_STATE = 0x01
    const val F_MANEUVER_STATE = 0x02
    const val F_CURRENT_ROAD = 0x03
    const val F_DESTINATION = 0x04
    const val F_ETA = 0x05
    const val F_TIME_REMAINING = 0x06
    const val F_DISTANCE_REMAINING = 0x07
    const val F_DISTANCE_TO_MANEUVER = 0x0A
    const val F_MANEUVER_LIST = 0x0D
    const val F_MANEUVER_COUNT = 0x0E
    const val F_SHOWN_IN_APP = 0x0F
    const val F_SOURCE_NAME = 0x13
    const val F_SUPPORTS_RG = 0x14

    // ---- 0x5202 字段 ID ----
    const val M_INDEX = 0x01
    const val M_DESCRIPTION = 0x02
    const val M_TYPE = 0x03
    const val M_AFTER_ROAD = 0x04
    const val M_DISTANCE_BETWEEN = 0x05
    const val M_DRIVING_SIDE = 0x08
    const val M_EXIT_ANGLE = 0x0B

    // ---- RouteGuidanceState（0x01）----
    const val STATE_NO_ROUTE = 0
    const val STATE_ROUTE_SET = 1
    const val STATE_REROUTING = 5

    // ---- EManeuverType（0x5202/0x03，0–53，maneuver-mapping 逆向全表）----
    const val TYPE_NO_TURN = 0
    const val TYPE_LEFT_TURN = 1
    const val TYPE_RIGHT_TURN = 2
    const val TYPE_STRAIGHT_AHEAD = 3
    const val TYPE_U_TURN = 4
    const val TYPE_ENTER_ROUNDABOUT = 6
    const val TYPE_EXIT_ROUNDABOUT = 7
    const val TYPE_OFF_RAMP = 8
    const val TYPE_ARRIVE_AT_DESTINATION = 12
    const val TYPE_KEEP_LEFT = 13
    const val TYPE_KEEP_RIGHT = 14

    /** 驾驶侧（0x5202/0x08）：0 = 右舵（靠左行驶取 1，按车机映射镜像图标）。 */
    const val DRIVING_SIDE_RIGHT = 0

    /** 出口角哨兵：0/±1000 = 未提供（maneuver-mapping 约定）。 */
    const val ANGLE_ABSENT = 0

    /** iAP2 字符串参数：UTF-8 + NUL 结尾（Iap2Csm 规范）。 */
    private fun stringParam(
        id: Int,
        text: String,
    ): ByteArray = Iap2Csm.param(id, text.encodeToByteArray() + byteArrayOf(0))

    /**
     * 0x5201 RouteGuidanceUpdate 体。
     *
     * 激活判定三字段自洽约束（accessoryd 激活逻辑逆向）：
     * `shown`(0x0F)/`state`(0x01)/`maneuverCount`(0x0E) 必须一致——
     * shown=1 而 count=0 会被车机判为异常态。
     */
    fun guidanceBody(
        state: Int = STATE_ROUTE_SET,
        roadName: String,
        destination: String = roadName,
        distanceRemainingM: Long = 2_000,
        distanceToManeuverM: Long = 500,
        maneuverCount: Int = 1,
        shownInApp: Int = 1,
        supportsRouteGuidance: Int = 1,
    ): ByteArray {
        val parts =
            mutableListOf(
                Iap2Csm.u8(F_STATE, state),
                stringParam(F_CURRENT_ROAD, roadName),
                stringParam(F_DESTINATION, destination),
                Iap2Csm.u32(F_DISTANCE_REMAINING, distanceRemainingM),
                Iap2Csm.u32(F_DISTANCE_TO_MANEUVER, distanceToManeuverM),
            )
        // 0x0D maneuver 顺序表：mock 单槽位 [0]
        if (maneuverCount > 0) parts += Iap2Csm.param(F_MANEUVER_LIST, byteArrayOf(0))
        parts +=
            listOf(
                Iap2Csm.u8(F_MANEUVER_COUNT, maneuverCount),
                Iap2Csm.u8(F_SHOWN_IN_APP, shownInApp),
                stringParam(F_SOURCE_NAME, "CarDeck HUD mock"),
                Iap2Csm.u8(F_SUPPORTS_RG, supportsRouteGuidance),
            )
        return concat(*parts.toTypedArray())
    }

    /** 0x5202 RouteGuidanceManeuverUpdate 体（单 maneuver 槽位）。 */
    fun maneuverBody(
        index: Int = 0,
        type: Int = TYPE_RIGHT_TURN,
        description: String,
        afterRoadName: String = description,
        distanceBetweenM: Long = 500,
        drivingSide: Int = DRIVING_SIDE_RIGHT,
        exitAngle: Int = ANGLE_ABSENT,
    ): ByteArray =
        concat(
            Iap2Csm.u8(M_INDEX, index),
            stringParam(M_DESCRIPTION, description),
            Iap2Csm.u8(M_TYPE, type),
            stringParam(M_AFTER_ROAD, afterRoadName),
            Iap2Csm.u32(M_DISTANCE_BETWEEN, distanceBetweenM),
            Iap2Csm.u8(M_DRIVING_SIDE, drivingSide),
            // 出口角 signed BE16（rgd-tlv 实证）
            Iap2Csm.param(
                M_EXIT_ANGLE,
                byteArrayOf(((exitAngle shr 8) and 0xFF).toByte(), (exitAngle and 0xFF).toByte()),
            ),
        )

    /** NO_ROUTE 空态体（0x5200 订阅应答 / 导航收尾共用）：HUD 灭卡的唯一可靠信号。 */
    fun noRouteBody(): ByteArray =
        guidanceBody(
            state = STATE_NO_ROUTE,
            roadName = "",
            destination = "",
            distanceRemainingM = 0,
            distanceToManeuverM = 0,
            maneuverCount = 0,
            shownInApp = 0,
            supportsRouteGuidance = 0,
        )

    /**
     * mock 导航序列：0x5201 骨架 → 0x5202 槽位详情（先列表骨架后槽位的发送顺序约定）。
     * [text] 同时作为路名与指令文本；距离/类型取默认（右转 500m）。
     */
    fun mockSequence(text: String): List<Pair<Int, ByteArray>> {
        val content = text.ifBlank { "模拟导航" }
        return listOf(
            MSG_UPDATE to
                guidanceBody(
                    roadName = content,
                    destination = content,
                    distanceToManeuverM = 500,
                ),
            MSG_MANEUVER to
                maneuverBody(
                    type = TYPE_RIGHT_TURN,
                    description = content,
                    afterRoadName = content,
                ),
        )
    }

    // ---- 发送委托（AirPlayClient 建链后注册；null = 无活动链路）----

    /**
     * CSM 出站委托：(messageId, body) -> null=已入队 / 非 null=失败原因
     * （[VirtualIap2Link.sendCsmFromUi] 的细分诊断文案）。
     */
    @Volatile
    var transmit: ((Int, ByteArray) -> String?)? = null

    /** 链路侧事件通知（0x5200 订阅到达等）→ 测试页日志窗口。 */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    /**
     * 车机是否已订阅 Route Guidance（0x5200 到达置位、0x5203/会话重置清零）。
     *
     * 协议语义：车机发 0x5200 StartRouteGuidanceUpdates 后手机才开始上报 0x5201/0x5202
     * （真 iPhone 同款）。未订阅先发在 CAMRY（德赛西威）实测中疑似触发车机拆 CarPlay
     * 会话（2026-10-26 日志：发送后 4s 车机 NSD 消失/WiFi 断/连接 abort）。
     */
    @Volatile
    var subscribed: Boolean = false

    /** 订阅状态一行摘要（测试页展示）。 */
    fun describeSubscription(): String =
        if (subscribed) {
            "车机已订阅 Route Guidance（0x5200 已到）——可正常发送"
        } else {
            "⚠ 车机未订阅 Route Guidance（0x5200 未到）——先在车机 CarPlay 起导航触发订阅；" +
                "未订阅强发可能触发车机拆会话（CAMRY 实测）"
        }

    /**
     * 发送 mock 导航序列，返回逐帧日志行（UI 日志窗口直接展示）。
     * 未订阅时首行带⚠警示（不拦截——mock 测试需要保留强发能力，风险已告知）。
     */
    fun sendMock(text: String): List<String> {
        val tx = transmit ?: return listOf("✗ 无活动 iAP2 链路（先连接车机）")
        val content = text.ifBlank { "模拟导航" }
        val lines = mutableListOf<String>()
        if (!subscribed) lines += "⚠ ${describeSubscription()}"
        for ((msgId, body) in mockSequence(text)) {
            val error = tx(msgId, body)
            lines +=
                if (error == null) {
                    "→ 0x${msgId.toString(16)} ${Iap2MessageIds.nameOf(msgId)} ${body.size}B " +
                        "「$content」已入队"
                } else {
                    "✗ 0x${msgId.toString(16)} ${Iap2MessageIds.nameOf(msgId)} 发送失败：$error"
                }
        }
        return lines
    }

    /** 发送 NO_ROUTE 收尾（HUD 灭卡），返回单行日志。 */
    fun sendNoRoute(): String {
        val tx = transmit ?: return "✗ 无活动 iAP2 链路（先连接车机）"
        val error = tx(MSG_UPDATE, noRouteBody())
        return if (error == null) {
            "→ 0x5201 ${Iap2MessageIds.nameOf(MSG_UPDATE)} NO_ROUTE 已入队（HUD 应收起）"
        } else {
            "✗ 0x5201 ${Iap2MessageIds.nameOf(MSG_UPDATE)} NO_ROUTE 发送失败：$error"
        }
    }

    /** 拼接 TLV 片段（比 ByteArrayOutputStream 更直白，纯组帧无 IO 语义）。 */
    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
