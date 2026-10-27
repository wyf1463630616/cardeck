package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RouteGuidanceSender 组帧自洽单测（车机解析器整帧校验约束）：
 * 车机解析器整帧校验——每条 TLV `len >= 4` 且不越界，残帧整条丢弃。
 * 这里验证组出来的 body 能被 [Iap2Csm.parseParams] 完整解析回字段。
 */
class RouteGuidanceSenderTest {
    // ---- TLV 自洽 ----

    @Test
    fun guidanceBodyTlvsAreSelfConsistent() {
        val body =
            RouteGuidanceSender.guidanceBody(
                roadName = "深南大道",
                destination = "深圳市市民中心",
                distanceRemainingM = 2_000,
                distanceToManeuverM = 500,
                maneuverCount = 1,
            )
        val params = Iap2Csm.parseParams(body)
        // 完整解析且无残留：逐条 len >= 4、总长与 body 一致由 parseParams 隐式校验
        // 10 条 = state/road/destination/distRemaining/distToManeuver/list/count/shown/source/supports
        assertEquals(10, params.size)
        val byId = params.associateBy { it.id }
        // 0x01 state = ROUTE_SET(1)
        assertEquals(1, byId.getValue(RouteGuidanceSender.F_STATE).value[0].toInt())
        // 0x0E maneuverCount = 1
        assertEquals(1, byId.getValue(RouteGuidanceSender.F_MANEUVER_COUNT).value[0].toInt())
        // 0x0F shown = 1（与 count 自洽）
        assertEquals(1, byId.getValue(RouteGuidanceSender.F_SHOWN_IN_APP).value[0].toInt())
        // 0x14 supports = 1
        assertEquals(1, byId.getValue(RouteGuidanceSender.F_SUPPORTS_RG).value[0].toInt())
        // 0x0D maneuver 列表 = [0]
        assertArrayEquals(byteArrayOf(0), byId.getValue(RouteGuidanceSender.F_MANEUVER_LIST).value)
    }

    @Test
    fun stringFieldsAreNulTerminated() {
        val body = RouteGuidanceSender.guidanceBody(roadName = "路名")
        val params = Iap2Csm.parseParams(body).associateBy { it.id }
        val road = params.getValue(RouteGuidanceSender.F_CURRENT_ROAD).value
        assertEquals(0, road.last().toInt()) // UTF-8 + NUL 结尾（Iap2Csm 规范）
        assertEquals("路名", road.decodeToString().trimEnd('\u0000'))
    }

    // ---- NO_ROUTE 空态（0x5200 订阅应答 / HUD 灭卡）----

    @Test
    fun noRouteBodySignalsShutdown() {
        val params = Iap2Csm.parseParams(RouteGuidanceSender.noRouteBody()).associateBy { it.id }
        assertEquals(0, params.getValue(RouteGuidanceSender.F_STATE).value[0].toInt())
        assertEquals(0, params.getValue(RouteGuidanceSender.F_MANEUVER_COUNT).value[0].toInt())
        assertEquals(0, params.getValue(RouteGuidanceSender.F_SHOWN_IN_APP).value[0].toInt())
        assertEquals(0, params.getValue(RouteGuidanceSender.F_SUPPORTS_RG).value[0].toInt())
    }

    // ---- mock 序列 ----

    @Test
    fun mockSequenceIsGuidanceThenManeuver() {
        val seq = RouteGuidanceSender.mockSequence("500m 后右转")
        assertEquals(2, seq.size)
        assertEquals(Iap2MessageIds.ROUTE_GUIDANCE_UPDATE, seq[0].first)
        assertEquals(Iap2MessageIds.ROUTE_GUIDANCE_MANEUVER_UPDATE, seq[1].first)
    }

    @Test
    fun mockSequenceCarriesTextIntoBothFrames() {
        val seq = RouteGuidanceSender.mockSequence("深南大道")
        val guidance = Iap2Csm.parseParams(seq[0].second).associateBy { it.id }
        val maneuver = Iap2Csm.parseParams(seq[1].second).associateBy { it.id }
        val road =
            guidance
                .getValue(RouteGuidanceSender.F_CURRENT_ROAD)
                .value
                .decodeToString()
                .trimEnd('\u0000')
        val desc =
            maneuver
                .getValue(RouteGuidanceSender.M_DESCRIPTION)
                .value
                .decodeToString()
                .trimEnd('\u0000')
        assertEquals("深南大道", road)
        assertEquals("深南大道", desc)
        // 默认档：右转（EManeuverType=2）+ 500m
        assertEquals(
            RouteGuidanceSender.TYPE_RIGHT_TURN.toByte(),
            maneuver.getValue(RouteGuidanceSender.M_TYPE).value[0],
        )
    }

    @Test
    fun blankTextFallsBackToPlaceholder() {
        val seq = RouteGuidanceSender.mockSequence("")
        val guidance = Iap2Csm.parseParams(seq[0].second).associateBy { it.id }
        val road =
            guidance
                .getValue(RouteGuidanceSender.F_CURRENT_ROAD)
                .value
                .decodeToString()
                .trimEnd('\u0000')
        assertEquals("模拟导航", road)
    }

    // ---- maneuver 体 ----

    @Test
    fun maneuverExitAngleIsSignedBe16() {
        val body =
            RouteGuidanceSender.maneuverBody(
                description = "右转",
                exitAngle = -90,
            )
        val params = Iap2Csm.parseParams(body).associateBy { it.id }
        val angle = params.getValue(RouteGuidanceSender.M_EXIT_ANGLE).value
        assertEquals(2, angle.size)
        val decoded = ((angle[0].toInt() and 0xFF) shl 8) or (angle[1].toInt() and 0xFF)
        // -90 的 16 位补码 = 0xFFA6
        assertEquals(0xFFA6, decoded)
    }

    @Test
    fun mockFramesParseBackWithoutResidue() {
        // 整帧自洽回归：Iap2Csm.encode 后再 Parser 解析回来，body 逐字节一致
        for ((msgId, body) in RouteGuidanceSender.mockSequence("测试")) {
            val frame = Iap2Csm.encode(msgId, body)
            val parsed = Iap2Csm.Parser().offer(frame)
            assertEquals(1, parsed.size)
            assertEquals(msgId, parsed[0].messageId)
            assertArrayEquals(body, parsed[0].body)
            assertTrue(Iap2Csm.parseParams(parsed[0].body).all { it.value.size >= 0 })
        }
    }
}
