package io.github.shaonialife.cardeck.privilege

import android.content.Context
import android.os.IBinder
import android.util.Log
import io.github.shaonialife.cardeck.shizuku.ICarInput
import io.github.shaonialife.cardeck.util.Bg

/**
 * root daemon 的应用侧客户端：拉起常驻进程 + 从 ServiceManager 取 [ICarInput] binder。
 *
 * 与 [io.github.shaonialife.cardeck.shizuku.CarInputClient]（Shizuku UserService 路径）
 * 产出同一 [ICarInput] 接口——TouchRouter/VirtualLauncher 等调用方零改动。
 *
 * 生命周期约定：
 * - daemon 随宿主 App 消失自尽（RootDaemonEntry watchdog 比对 appPid）；
 * - 本进程只在 [ensure] 时按需拉起（daemon 已在则直连，不重复 spawn）；
 * - [reset] 主动停掉 daemon（root 探测失败回退 Shizuku 时清理现场）。
 */
object RootDaemonClient {
    private const val TAG = "RootDaemonClient"

    /** ServiceManager 服务名（daemon 注册 / 客户端获取共用）。 */
    const val SERVICE_NAME = "cardeck.carinput"

    /** daemon 进程 nice-name（ps 识别 / 清理旧进程用）。 */
    private const val PROCESS_NAME = "cardeck_root_daemon"

    @Volatile
    var service: ICarInput? = null
        private set

    /** 诊断日志出口（接入文件日志，HKS 加密下 logcat 不可读）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    @Volatile
    private var spawning = false

    @Volatile
    private var lastSpawnAttemptAt = 0L

    private fun diag(
        message: String,
        force: Boolean = false,
    ) {
        Log.i(TAG, message)
        if (force) {
            logSink?.invoke("ROOTDAEMON: $message")
        } else {
            logSink?.invoke("ROOTDAEMON: $message")
        }
    }

    /**
     * 确保 root daemon 已连通。可高频调用：service 为空且距上次尝试 >3s 时自动重拉。
     * 阻塞（含 su 进程创建 + binder 等待），**只允许后台线程调用**。
     */
    fun ensure(context: Context) {
        service?.let { s ->
            // binder 仍活着（ping 走一次轻量事务）则直接复用
            if (runCatching { s.ping() }.getOrDefault(false)) return
            service = null
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSpawnAttemptAt < 3_000L) return
        synchronized(this) {
            if (spawning) return
            spawning = true
        }
        lastSpawnAttemptAt = now
        try {
            // 1) 先查 ServiceManager（daemon 可能已在跑——例如连接中重建）
            bindFromServiceManager()?.let {
                service = it
                diag("已连上在跑的 daemon（ping=${runCatching { it.ping() }.getOrDefault(false)}）", force = true)
                return
            }
            // 2) 拉起 daemon（su 进程创建，秒级）
            spawn(context)
            // 3) 轮询等注册（daemon 内 systemMain + setuid + 注册约 1~3s）
            val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                bindFromServiceManager()?.let {
                    service = it
                    diag("daemon 拉起成功（${8_000L - (deadline - android.os.SystemClock.uptimeMillis())}ms）", force = true)
                    return
                }
                Thread.sleep(120)
            }
            diag("daemon 注册超时（8s）——SELinux 拦 addService？root 后端本次不可用", force = true)
        } finally {
            spawning = false
        }
    }

    private fun bindFromServiceManager(): ICarInput? =
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder =
                sm
                    .getMethod("getService", String::class.java)
                    .invoke(null, SERVICE_NAME) as? IBinder
                    ?: return null
            val svc = ICarInput.Stub.asInterface(binder)
            if (runCatching { svc.ping() }.getOrDefault(false)) svc else null
        } catch (t: Throwable) {
            Log.w(TAG, "getService($SERVICE_NAME) 失败：$t")
            null
        }

    private fun spawn(context: Context) {
        val apkPath = context.applicationInfo.sourceDir
        val appPid = android.os.Process.myPid()
        val appPackage = context.packageName
        val cmd =
            "CLASSPATH=$apkPath app_process /system/bin " +
                "--nice-name=$PROCESS_NAME " +
                "io.github.shaonialife.cardeck.privilege.RootDaemonEntry $appPid $appPackage"
        diag("spawn: $cmd", force = true)
        Bg.run("root-daemon-spawn") {
            try {
                val process =
                    ProcessBuilder("su", "-c", cmd)
                        .redirectErrorStream(true)
                        .start()
                // daemon 是常驻的——这里不 waitFor，只转存输出供诊断
                Bg.run("root-daemon-stdout") {
                    try {
                        process.inputStream.bufferedReader().use { r ->
                            r.lineSequence().forEach { line ->
                                if (line.isNotBlank()) diag("daemon: $line")
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                diag("spawn 失败：${t.message}", force = true)
            }
        }
    }

    /** 主动停掉 root daemon（进程名匹配 kill；回退 Shizuku / 手动重置用）。 */
    fun reset() {
        service = null
        Bg.run("root-daemon-reset") {
            try {
                val process =
                    ProcessBuilder("su", "-c", "pkill -f $PROCESS_NAME")
                        .redirectErrorStream(true)
                        .start()
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                diag("reset: 已请求 daemon 退出", force = true)
            } catch (t: Throwable) {
                diag("reset 失败：${t.message}")
            }
        }
    }
}
