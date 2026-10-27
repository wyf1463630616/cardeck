package io.github.shaonialife.cardeck.launcher.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.airplay.RouteGuidanceSender
import io.github.shaonialife.cardeck.util.Bg
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 「HUD 测试」面板：模拟发送 iAP2 Route Guidance 导航事件（0x5201/0x5202），
 * 验证车机 HUD 是否渲染转向指令（协议与字段见 RouteGuidanceSender 注释）。
 *
 * 布局：上半输入框 + 发送/结束按钮；下半（weight=1）日志窗口，记录本页收发
 * 与链路侧事件（0x5200 订阅到达），自动滚动到底部。
 *
 * 线程约定（AGENTS.md）：发送走 [Bg] 短命任务（sendCsmFromUi 内部有文件日志写入），
 * 日志窗口更新统一回主线程。
 */
class HudMockPanelFragment : SettingsPanelFragment() {
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 日志缓冲（首行是占位引导，最多 [MAX_LOG_LINES] 行防膨胀）。 */
    private val logLines = ArrayDeque<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_hud_mock, container, false)

    override fun onViewCreated(view: View) {
        val input = view.findViewById<EditText>(R.id.hudMockInput)
        logView = view.findViewById(R.id.hudMockLog)
        logScroll = view.findViewById(R.id.hudMockLogScroll)

        // 链路侧事件（0x5200 订阅到达等）→ 本页日志（反注册见 onDestroyView）
        RouteGuidanceSender.onEvent = { line ->
            mainHandler.post { appendLog(line) }
        }

        // 打开即提示链路状态（发送失败的具体原因由 sendCsmFromUi 细分给出）
        appendLog(
            if (RouteGuidanceSender.transmit == null) {
                "当前无活动 iAP2 链路——请先连接车机，再点发送"
            } else {
                "链路发送通道就绪，可发送 mock 事件"
            },
        )
        appendLog(RouteGuidanceSender.describeSubscription())

        view.findViewById<TextView>(R.id.btnHudSend).setOnClickListener {
            val text = input.text?.toString().orEmpty()
            Bg.run("hud-mock-send") {
                val lines = RouteGuidanceSender.sendMock(text)
                mainHandler.post { lines.forEach(::appendLog) }
            }
        }

        view.findViewById<TextView>(R.id.btnHudStop).setOnClickListener {
            Bg.run("hud-mock-stop") {
                val line = RouteGuidanceSender.sendNoRoute()
                mainHandler.post { appendLog(line) }
            }
        }
    }

    override fun onDestroyView() {
        // 必须清：回调持有视图更新，页面销毁后链路事件再进来会更新死视图
        RouteGuidanceSender.onEvent = null
        logView = null
        logScroll = null
        super.onDestroyView()
    }

    /** 追加一行日志（主线程调用）：时间戳前缀 + 自动滚动。 */
    private fun appendLog(line: String) {
        val stamp = timeFormat.format(Date())
        logLines.addLast("$stamp  $line")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        val view = logView ?: return
        view.text = logLines.joinToString("\n")
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val MAX_LOG_LINES = 200
    }
}
