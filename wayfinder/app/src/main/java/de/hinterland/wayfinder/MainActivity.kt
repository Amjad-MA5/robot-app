package de.hinterland.wayfinder

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ainirobot.agent.AgentCore
import com.ainirobot.agent.OnAgentStatusChangedListener
import com.ainirobot.agent.OnTranscribeListener
import com.ainirobot.agent.PageAgent
import com.ainirobot.agent.base.Transcription
import de.hinterland.wayfinder.core.AppLog
import de.hinterland.wayfinder.flow.GuideViewModel
import de.hinterland.wayfinder.flow.Mode
import de.hinterland.wayfinder.ui.AdminScreen
import de.hinterland.wayfinder.ui.MainActions
import de.hinterland.wayfinder.ui.MainScreen
import de.hinterland.wayfinder.ui.PinDialog
import de.hinterland.wayfinder.ui.ScaledToRobotScreen
import de.hinterland.wayfinder.ui.theme.WayfinderTheme

class MainActivity : ComponentActivity() {

    private val vm: GuideViewModel by viewModels()
    private var pageAgent: PageAgent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        if (vm.onRobot) setupPageAgent()
        setContent {
            WayfinderTheme {
                WayfinderRoot(vm)
            }
        }
    }

    /** One PageAgent for the page (SDK rule). Speech is handled by the app itself, so no actions. */
    private fun setupPageAgent() {
        try {
            pageAgent = PageAgent(this)
                .setObjective("Help event visitors find rooms: walk them there or tell them on which floor a room is.")
                .setOnTranscribeListener(object : OnTranscribeListener {
                    override fun onASRResult(transcription: Transcription): Boolean {
                        vm.onSpeech(transcription.text, transcription.final)
                        return true // the app shows its own captions
                    }

                    override fun onTTSResult(transcription: Transcription): Boolean = true
                })
                .setOnAgentStatusChangedListener(object : OnAgentStatusChangedListener {
                    override fun onStatusChanged(status: String, message: String?): Boolean {
                        vm.onAgentStatus(status)
                        return true
                    }
                })
        } catch (t: Throwable) {
            AppLog.e("Could not create the PageAgent (is AgentOS installed?)", t)
        }
    }

    /** AgentOS defaults for this app; set again whenever the app comes to the front. */
    private fun applyAgentSettings() {
        if (!vm.onRobot) return
        runCatching {
            AgentCore.isDisablePlan = true // the app decides what to do with speech (no automatic actions)
            AgentCore.isEnableVoiceBar = false
            AgentCore.isMicrophoneMuted = false
            AgentCore.clearContext()
        }.onFailure { AppLog.w("AgentCore is not available", it) }
    }

    override fun onStart() {
        super.onStart()
        applyAgentSettings()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun WayfinderRoot(vm: GuideViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var showPin by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }

    if (showAdmin) {
        AdminScreen(
            vm = vm,
            onClose = {
                showAdmin = false
                vm.setAdminOpen(false)
            },
        )
    } else {
        ScaledToRobotScreen {
            MainScreen(
                state = state,
                actions = MainActions(
                    onAnyTouch = vm::onAnyTouch,
                    onRoom = vm::onRoomTapped,
                    onStop = vm::onStop,
                    onWay = vm::onWayButton,
                    onCloseCard = vm::onCloseCard,
                    onAdmin = { showPin = true },
                ),
            )
        }
        // Visitors can never leave the app with "back"; it stops the robot or closes the card instead
        BackHandler {
            when (state.mode) {
                Mode.GUIDING, Mode.WANDERING, Mode.RETURNING -> vm.onStop()
                Mode.INFO, Mode.ARRIVED -> vm.onCloseCard()
                else -> Unit
            }
        }
    }
    if (showPin) {
        PinDialog(
            expectedPin = vm.settings.adminPin,
            onSuccess = {
                showPin = false
                showAdmin = true
                vm.setAdminOpen(true)
            },
            onDismiss = { showPin = false },
        )
    }
}
