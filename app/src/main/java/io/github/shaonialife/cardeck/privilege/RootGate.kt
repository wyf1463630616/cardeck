package io.github.shaonialife.cardeck.privilege

import android.util.Log
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * root 可用性探测（实测 `su -c id` 而非只看 su 二进制在场——部分 ROM 有 su 壳但拒绝提权）。
 *
 * 检测策略（2026-10-21 定稿）：所有原本触发 Shizuku 检测的时机**先**触发 root 检测；
 * 成功则缓存、此后全部优先走 root（不再重复探测，也免得反复弹 Magisk/KernelSU 授权窗）；
 * 失败则回退原 Shizuku 链路。缓存只存进程内存，重启 App 重新探测。
 *
 * 探测内含 su 进程创建 + 首次可能弹授权窗，**必须在后台线程调用**（[probeBlocking]）；
 * 主线程只读非阻塞的 [cached]。
 */
object RootGate {
    private const val TAG = "RootGate"

    enum class Probe { UNKNOWN, ROOT, NO_ROOT }

    @Volatile
    private var probe: Probe = Probe.UNKNOWN

    /** 非阻塞读缓存（主线程可用）。UNKNOWN = 尚未探测或上次超时未决。 */
    fun cached(): Probe = probe

    /**
     * 阻塞探测（后台线程调用）。`su -c id` 输出含 `uid=0` 即判定有 root。
     *
     * 超时（典型：Magisk 授权窗没人点）**不写缓存**——下次还能再探，
     * 否则一次错过授权就被永久判死。确定性的失败（su 不存在/拒绝）才写 NO_ROOT。
     */
    fun probeBlocking(timeoutMs: Long = PROBE_TIMEOUT_MS): Boolean {
        if (probe != Probe.UNKNOWN) return probe == Probe.ROOT
        return try {
            val process =
                ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
            // 先等退出再读输出：`id` 输出仅几十字节，不会撑满管道；反过来读会因
            // 授权窗未决而永久阻塞在 read() 上，连超时都进不去。
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroy()
                logW("root 探测超时（${timeoutMs}ms）——授权窗未决？本次不写缓存")
                false
            } else {
                val output =
                    try {
                        InputStreamReader(process.inputStream).use { it.readText() }
                    } catch (_: Throwable) {
                        ""
                    }
                val root = process.exitValue() == 0 && output.contains("uid=0")
                probe = if (root) Probe.ROOT else Probe.NO_ROOT
                logW("root 探测完成：root=$root（exit=${process.exitValue()}）")
                root
            }
        } catch (t: Throwable) {
            // su 命令不存在 / 无法启动 = 确定性无 root
            probe = Probe.NO_ROOT
            logW("root 探测失败（判无 root）：$t")
            false
        }
    }

    /** 清缓存重探（「重试检查」按钮用）。 */
    fun reprobe(timeoutMs: Long = PROBE_TIMEOUT_MS): Boolean {
        probe = Probe.UNKNOWN
        return probeBlocking(timeoutMs)
    }

    /**
     * 日志容错：JVM 单测无 android.util.Log（未 mock 直接抛 RuntimeException），
     * 吞掉日志异常——日志失败不该影响探测判定。
     */
    private fun logW(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private const val PROBE_TIMEOUT_MS = 15_000L
}
