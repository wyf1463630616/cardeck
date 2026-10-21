package io.github.shaonialife.cardeck.privilege

import java.io.InputStreamReader

/**
 * root 通道的 shell 执行：`su -c <cmd>`。
 *
 * 与 Shizuku 侧 `Shell.exec` 保持同一 Result 语义（exitCode/output），
 * 由 [PrivilegeGate] 统一分发，调用方（8 文件 40 处）无感知。
 */
object RootShell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** `su -c id` 探测之外的通用命令执行（后台线程调用；su 本身有进程创建开销）。 */
    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        return try {
            val process =
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
            val exited = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroy()
                return Result(-1, "命令执行超时（${timeoutMs}ms）")
            }
            val output =
                try {
                    InputStreamReader(process.inputStream).use { it.readText().trim() }
                } catch (_: Throwable) {
                    ""
                }
            Result(process.exitValue(), output)
        } catch (t: Throwable) {
            Result(-1, "root 命令执行失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}
