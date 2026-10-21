package io.github.shaonialife.cardeck.privilege

import android.content.Context
import io.github.shaonialife.cardeck.shizuku.ShizukuGate

/**
 * 特权后端选择：root 优先、Shizuku 回退（2026-10-21 需求）。
 *
 * 策略：
 * - 所有原本触发 Shizuku 检测的时机，**先**触发 root 检测；
 * - root 成功 → 缓存并全程优先 root，不再重复检测（避免反复弹 su 授权窗）；
 * - root 失败 → 回退原 Shizuku 检测与链路，行为与改造前一致。
 *
 * 返回状态沿用 [ShizukuGate.State]：root 就绪映射为 READY，
 * 调用方（MainActivity/SessionController/HiddenDisplaySource/VirtualLauncher）
 * 的就绪判定逻辑零改动。
 */
object PrivilegeGate {
    /** 当前实际生效的后端（诊断/日志用）。 */
    enum class Backend { ROOT, SHIZUKU, NONE }

    /** 上次 resolve 的结果（诊断用，非缓存依据——缓存只认 RootGate）。 */
    @Volatile
    var lastBackend: Backend = Backend.NONE
        private set

    /**
     * 解析特权就绪状态：root 优先 → Shizuku 回退。
     *
     * **root 探测含 su 进程创建（首次可能弹授权窗），只允许后台线程调用本方法**；
     * 主线程 UI 只读 [RootGate.cached]（见 MainActivity.updateShizukuRow）。
     */
    fun resolve(context: Context): ShizukuGate.State {
        // root 优先：已缓存 ROOT 直接放行（不再碰 Shizuku）
        if (RootGate.cached() == RootGate.Probe.ROOT) {
            lastBackend = Backend.ROOT
            return ShizukuGate.State.READY
        }
        // 未决/未知 → 实测一次（后台线程）
        if (RootGate.probeBlocking()) {
            lastBackend = Backend.ROOT
            return ShizukuGate.State.READY
        }
        // root 不可用 → 回退原 Shizuku 链路
        val shizuku = ShizukuGate.state(context)
        lastBackend = if (shizuku == ShizukuGate.State.READY) Backend.SHIZUKU else Backend.NONE
        return shizuku
    }

    /**
     * 主线程安全版：只读缓存，不触发探测。
     * root 已缓存 ROOT → READY；否则返回 Shizuku 当前态（与改造前一致）。
     */
    fun resolveCached(context: Context): ShizukuGate.State {
        if (RootGate.cached() == RootGate.Probe.ROOT) {
            lastBackend = Backend.ROOT
            return ShizukuGate.State.READY
        }
        val shizuku = ShizukuGate.state(context)
        lastBackend = if (shizuku == ShizukuGate.State.READY) Backend.SHIZUKU else Backend.NONE
        return shizuku
    }

    /** 当前是否走 root 后端（诊断/日志/状态行区分用）。 */
    fun isRootBackend(): Boolean = RootGate.cached() == RootGate.Probe.ROOT

    /** 清缓存重探（「重试检查」按钮用，主线程调用后需自行转后台执行探测）。 */
    fun reprobe(): Boolean = RootGate.reprobe()

    /** 未就绪引导文案：root 与 Shizuku 两路（纯函数，可单测）。 */
    internal fun guidanceOf(state: ShizukuGate.State): ShizukuGuidance =
        when {
            state == ShizukuGate.State.READY ->
                ShizukuGuidance(
                    title = if (isRootBackend()) "root 已就绪" else "Shizuku 就绪",
                    message = "",
                    primaryLabel = null,
                )

            RootGate.cached() == RootGate.Probe.NO_ROOT ->
                // root 已实测不可用 → 引导用户走 Shizuku（原文案复用）
                ShizukuGate.guidanceOf(state).let {
                    ShizukuGuidance(
                        title = it.title,
                        message = "root 不可用，以下为 Shizuku 方案。\n\n" + it.message,
                        primaryLabel = it.primaryLabel,
                    )
                }

            else ->
                // root 尚未决（探测超时）→ 提示两条路
                ShizukuGate.guidanceOf(state).let {
                    ShizukuGuidance(
                        title = it.title,
                        message =
                            "可选 root 方式：授予本应用 root 权限（Magisk/KernelSU 授权弹窗点「允许」）后回来点「重试检查」，" +
                                "检测到 root 将不再要求 Shizuku。\n\n" +
                                "或按以下 Shizuku 方案操作：\n\n" + it.message,
                        primaryLabel = it.primaryLabel,
                    )
                }
        }
}

/** 特权后端未就绪时的引导内容（见 [PrivilegeGate.guidanceOf]）。 */
internal data class ShizukuGuidance(
    val title: String,
    val message: String,
    /** null = 本机没有可执行的主操作（未安装时无处可跳）。 */
    val primaryLabel: String?,
)
