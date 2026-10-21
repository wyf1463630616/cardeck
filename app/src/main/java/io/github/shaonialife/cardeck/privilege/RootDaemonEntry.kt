package io.github.shaonialife.cardeck.privilege

import android.content.Context
import android.os.Looper
import android.util.Log
import io.github.shaonialife.cardeck.shizuku.CarInputServiceImpl
import java.io.File

/**
 * root daemon 主体：`su -c app_process` 拉起的常驻进程，uid 0 启动后 setuid(2000)
 * 对齐 Shizuku UserService 的 shell 权限画像，复用 [CarInputServiceImpl] 全部
 * 反射注入/受信虚拟屏逻辑，经 ServiceManager 注册 [ICarInput][io.github.shaonialife.cardeck.shizuku.ICarInput]
 * 供应用侧直连（毫秒级，与 Shizuku UserService 等价）。
 *
 * 关键点（对照 Shizuku UserService 的运行条件）：
 * - **uid 必须是 2000**：createDisplay 的 DisplayManagerService 校验
 *   「packageName 与调用 uid 匹配」——com.android.shell Context + uid 2000 才过；
 *   root(uid 0) 不匹配任何包名，会 SecurityException。
 * - **setuid 不改 SELinux 域**：进程仍处 su 域，ServiceManager.addService 的
 *   域检查不受影响（域检查在 add 时做一次，之后 binder 调用按进程 uid 计）。
 * - **app_process 自动 join binder 线程池**（app_main.cpp 的 onStarted），
 *   无需手动起池；主线程 Looper.loop() 仅保活。
 * - **appContext 而非 shellContext**：CarInputServiceImpl 内部会自行
 *   createPackageContext("com.android.shell") 拿 DisplayManager；传入的 context
 *   只用于包信息/日志，传 app 包语义才正确（stale 自清比对的是本 app 的 lastUpdateTime）。
 *
 * 用法（RootDaemonClient 拼装）：
 * `app_process -Djava.class.path=<base.apk> /system/bin --nice-name=cardeck_root_daemon
 *  io.github.shaonialife.cardeck.privilege.RootDaemonEntry <appPid> <appPackage>`
 *
 * 退出条件：watchdog 检测到宿主 App 进程（appPid）消失 / 收到 SIGTERM。
 */
object RootDaemonEntry {
    private const val TAG = "RootDaemon"
    private const val SHELL_UID = 2000

    @JvmStatic
    fun main(args: Array<String>) {
        val appPid = args.getOrNull(0)?.toIntOrNull() ?: -1
        val appPackage = args.getOrNull(1) ?: "io.github.shaonialife.cardeck"
        fileLog("daemon start: pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} appPid=$appPid pkg=$appPackage")

        // 1) 系统 Context（app_process 无 Android Context，经 ActivityThread.systemMain 取）
        val systemContext =
            try {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getMethod("systemMain").invoke(null)
                at.getMethod("getSystemContext").invoke(thread) as Context
            } catch (t: Throwable) {
                fileLog("systemMain 失败，daemon 退出：$t")
                return
            }

        // 2) 先降权到 shell（2000）——后续 createDisplay 按此 uid 校验；
        //    SELinux 域不变（setuid 不触发域切换），addService 域检查不受影响
        try {
            android.system.Os.setgid(SHELL_UID)
            android.system.Os.setuid(SHELL_UID)
            fileLog("setuid($SHELL_UID) ok, uid=${android.os.Process.myUid()}")
        } catch (t: Throwable) {
            fileLog("setuid($SHELL_UID) 失败（createDisplay 受信屏大概率不可用）：$t")
        }

        // 3) app Context（CarInputServiceImpl 的包语义/自清逻辑依赖本 app 包名）
        val appContext =
            try {
                systemContext.createPackageContext(appPackage, 0)
            } catch (t: Throwable) {
                fileLog("createPackageContext($appPackage) 失败，回退 systemContext：$t")
                systemContext
            }

        // 4) 复用现有 UserService 实现（反射注入 + 受信屏 + IME 策略全在内）
        val impl = CarInputServiceImpl(appContext)

        // 5) 注册到 ServiceManager（域检查一次；应用侧 getService 拿 binder 直连）
        val registered =
            try {
                val sm = Class.forName("android.os.ServiceManager")
                sm
                    .getMethod("addService", String::class.java, android.os.IBinder::class.java)
                    .invoke(null, RootDaemonClient.SERVICE_NAME, impl)
                true
            } catch (t: Throwable) {
                fileLog("ServiceManager.addService 失败（SELinux 拒绝？root 后端不可用，应用侧将回退）：$t")
                false
            }
        if (!registered) return

        // 6) 宿主 App 消失则自尽（防孤儿常驻；其虚拟屏随进程回收）
        if (appPid > 0) {
            Thread({
                while (true) {
                    try {
                        Thread.sleep(5_000L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!File("/proc/$appPid").exists()) {
                        fileLog("宿主 App 进程 $appPid 已退出，daemon 自尽（虚拟屏随进程释放）")
                        android.os.Process.killProcess(android.os.Process.myPid())
                        return@Thread
                    }
                }
            }, "root-daemon-watchdog").apply {
                isDaemon = true
                start()
            }
        }

        fileLog("daemon ready: service=${RootDaemonClient.SERVICE_NAME}")
        Looper.prepareMainLooper()
        Looper.loop()
    }

    /** 文件日志：app_process 的 logcat 不可靠，写 /data/local/tmp 供 adb 读取。 */
    private fun fileLog(message: String) {
        Log.i(TAG, message)
        try {
            java.io.FileWriter(File("/data/local/tmp/cardeck_root_daemon.log"), true).use { w ->
                w
                    .append(
                        android.text.format.DateFormat
                            .format("HH:mm:ss.SSS", System.currentTimeMillis())
                            .toString(),
                    ).append(' ')
                    .append(message)
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
    }
}
