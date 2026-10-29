package vn.onplay.vdubbing.framework.audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.framework.locale.AppLocaleController

internal class AudioCapturePermissionActivity : ComponentActivity() {
    private val sessions get() = (application as VDubbingApp).container.audioSessions
    private val sessionId get() = intent.getStringExtra(EXTRA_SESSION).orEmpty()
    private var handedToService = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestCapture() else fail(R.string.audio_permission_denied)
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (sessions.state.value?.id != sessionId) {
            finish()
        } else if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                AudioTranslationService.start(this, sessionId, result.resultCode, data)
                handedToService = true
                finish()
            } catch (_: RuntimeException) {
                fail(R.string.audio_start_failed)
            }
        } else fail(R.string.audio_permission_denied)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleController.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (sessions.state.value?.id != sessionId) {
            finish()
            return
        }
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestCapture()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestCapture() {
        if (sessions.state.value?.id != sessionId) {
            finish()
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else manager.createScreenCaptureIntent()
            projection.launch(request)
        } catch (_: RuntimeException) {
            fail(R.string.audio_start_failed)
        }
    }

    private fun fail(message: Int) {
        sessions.end(sessionId)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !handedToService) sessions.end(sessionId)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION = "audio_session"

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, AudioCapturePermissionActivity::class.java)
                .putExtra(EXTRA_SESSION, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
