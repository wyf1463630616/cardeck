package io.github.shaonialife.cardeck.privilege

import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 特权后端引导文案（双后端：root 优先、Shizuku 回退）。
 *
 * JVM 单测环境无 su，[RootGate.cached] 恒为 UNKNOWN —— 正好覆盖
 * 「root 未决」这一最容易漏的中间态（用户该知道两条路都可走）。
 */
class PrivilegeGateGuidanceTest {
    @Before
    fun resetProbe() {
        // 清缓存并重探：JVM 无 su，ProcessBuilder 立刻 IOException → 判 NO_ROOT（确定态）。
        // 覆盖「root 已实测不可用 → 只留 Shizuku 方案」的引导分支。
        RootGate.reprobe()
        assertEquals(RootGate.Probe.NO_ROOT, RootGate.cached())
    }

    @Test
    fun readyGuidanceReflectsBackend() {
        val guidance = PrivilegeGate.guidanceOf(ShizukuGate.State.READY)
        assertNotNull(guidance)
        // 就绪态不该再给操作按钮
        assertNull(guidance.primaryLabel)
    }

    @Test
    fun notRunningGuidanceMentionsRootPath() {
        val guidance = PrivilegeGate.guidanceOf(ShizukuGate.State.NOT_RUNNING)
        // root 未决/失败时都要让用户知道「root 可替代 Shizuku」——这是本次需求的核心卖点
        assertTrue("文案应提及 root：${guidance.message}", guidance.message.contains("root"))
        // Shizuku 原引导动作不能丢（回退路径仍要用）
        assertEquals("打开 Shizuku", guidance.primaryLabel)
    }

    @Test
    fun unauthorizedGuidanceMentionsRootPath() {
        val guidance = PrivilegeGate.guidanceOf(ShizukuGate.State.UNAUTHORIZED)
        assertTrue(guidance.message.contains("root"))
        assertEquals("请求授权", guidance.primaryLabel)
    }

    @Test
    fun notInstalledGuidanceKeepsNoFakeAction() {
        val guidance = PrivilegeGate.guidanceOf(ShizukuGate.State.NOT_INSTALLED)
        // 未安装 Shizuku 时不该编造跳转按钮；root 提示仍要出现
        assertNull(guidance.primaryLabel)
        assertTrue(guidance.message.contains("root"))
    }

    @Test
    fun eachUnreadyStateHasDistinctTitle() {
        // 三态标题互不相同（沿用 Shizuku 引导的可诊断性要求）
        val titles =
            listOf(
                ShizukuGate.State.NOT_INSTALLED,
                ShizukuGate.State.NOT_RUNNING,
                ShizukuGate.State.UNAUTHORIZED,
            ).map { PrivilegeGate.guidanceOf(it).title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun rootGateNeverMisjudgesRootInJvm() {
        // JVM 无 su：探测绝不能误判 ROOT（误判会导致功能全走 root 通道静默失效）
        assertTrue(RootGate.cached() != RootGate.Probe.ROOT)
    }
}
