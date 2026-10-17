package de.hinterland.smartreceptionist

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ainirobot.agent.OnAgentStatusChangedListener
import com.ainirobot.agent.OnTranscribeListener
import com.ainirobot.agent.PageAgent
import com.ainirobot.agent.base.Transcription
import de.hinterland.smartreceptionist.flow.ReceptionViewModel
import de.hinterland.smartreceptionist.net.Employee
import de.hinterland.smartreceptionist.robot.RobotController
import de.hinterland.smartreceptionist.ui.AdminScreen
import de.hinterland.smartreceptionist.ui.PinDialog
import de.hinterland.smartreceptionist.ui.ReceptionActions
import de.hinterland.smartreceptionist.ui.ReceptionScreen
import de.hinterland.smartreceptionist.ui.ScaledToRobotScreen
import de.hinterland.smartreceptionist.ui.theme.ReceptionTheme
import de.hinterland.smartreceptionist.voice.linesFor

class MainActivity : ComponentActivity() {

    private val vm: ReceptionViewModel by viewModels()
    private var pageAgent: PageAgent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Fahrwerk/Navigation/Sensorik über die RobotAPI (nur im Vordergrund erlaubt)
        if (RobotController.isCoreServiceInstalled(this)) {
            RobotController.connect(this)
            setupPageAgent()
        }

        setContent {
            ReceptionTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                var showPin by remember { mutableStateOf(false) }
                var showAdmin by remember { mutableStateOf(false) }
                val lines = linesFor(vm.settings.language)

                if (showAdmin) {
                    AdminScreen(
                        initial = vm.settings,
                        onSave = { vm.updateSettings(it) },
                        onClose = {
                            showAdmin = false
                            vm.setAdminOpen(false)
                        },
                    )
                } else {
                    ScaledToRobotScreen {
                        ReceptionScreen(state, lines, object : ReceptionActions {
                            override fun onScreenTap() = vm.onScreenTap()
                            override fun onOption(id: String) { vm.choose(id) }
                            override fun onSubmitText(text: String) = vm.submitText(text)
                            override fun onPickEmployee(employee: Employee) { vm.pickEmployee(employee) }
                            override fun onOpenTeamsChat(employee: Employee) { openTeamsChat(employee) }
                            override fun onComposeEmail(employee: Employee) { composeEmail(employee) }
                            override fun onCancel() = vm.cancel()
                            override fun onStop() = vm.stopDriving()
                            override fun onAdminRequested() { showPin = true }
                        })
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
        }
    }

    private fun openTeamsChat(employee: Employee) {
        val message = linesFor(vm.settings.language).teamsDraftMessage(employee.name)
        val uri = Uri.Builder()
            .scheme("https")
            .authority("teams.microsoft.com")
            .appendPath("l")
            .appendPath("chat")
            .appendPath("0")
            .appendPath("0")
            .appendQueryParameter("users", employee.email.trim())
            .appendQueryParameter("message", message)
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Log.w("MainActivity", "No app can open the Teams chat link", e)
            Toast.makeText(
                this,
                linesFor(vm.settings.language).teamsLinkUnavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun composeEmail(employee: Employee) {
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("mailto", employee.email.trim(), null),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("MainActivity", "No app can compose email", e)
            Toast.makeText(
                this,
                linesFor(vm.settings.language).emailAppUnavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun setupPageAgent() {
        try {
            // Genau ein PageAgent pro Seite. Wir verarbeiten Spracherkennung selbst (Ablaufsteuerung).
            pageAgent = PageAgent(this)
                .setObjective("Check visitors in at reception and briefly answer questions about the process.")
                .setOnTranscribeListener(object : OnTranscribeListener {
                    override fun onASRResult(transcription: Transcription): Boolean {
                        vm.onAsr(transcription.text, transcription.final)
                        return true // eigene Untertitel statt System-Sprachleiste
                    }

                    override fun onTTSResult(transcription: Transcription): Boolean = true
                })
                .setOnAgentStatusChangedListener(object : OnAgentStatusChangedListener {
                    override fun onStatusChanged(status: String, message: String?): Boolean {
                        Log.d("MainActivity", "Agent-Status: $status ${message.orEmpty()}")
                        return true
                    }
                }) as PageAgent
        } catch (t: Throwable) {
            Log.e("MainActivity", "Could not create PageAgent (is AgentOS installed?)", t)
        }
    }

    override fun onStart() {
        super.onStart()
        RobotController.connect(this)
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
