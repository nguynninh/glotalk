package vn.onplay.vdubbing.framework.audio

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.domain.model.Language

class OnPlayAuditTest {
    @Test fun overflowTrace(): Unit = runBlocking {
        var stopped = false
        val requested = CompletableDeferred<Unit>()
        try {
            playOnPlayAudio(kotlinx.coroutines.flow.flow {
                try {
                    emit(byteArrayOf(1))
                    requested.await()
                    repeat(4) { emit(byteArrayOf((it + 2).toByte())) }
                } finally { stopped = true }
            }, translate = {
                requested.complete(Unit)
                awaitCancellation()
            }, play = { _, _ -> org.junit.Assert.fail("No server response") }, onStage = {})
            org.junit.Assert.fail("Expected queue overflow")
        } catch (_: OfflineAudioBacklogException) {
            Log.i("OnplayAudit", "event=controlled_overflow verified=true capture_stopped=$stopped at=${SystemClock.elapsedRealtime()}")
        }
        org.junit.Assert.assertTrue(stopped)
    }

    @Test fun fiveMinutes(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("onplayAudit") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val container = (context.applicationContext as VDubbingApp).container
        val oldOnplay = container.settings.onPlayAudioEnabled
        val oldGemini = container.settings.geminiLiveAudioEnabled
        val oldOpenai = container.settings.openAiRealtimeAudioEnabled
        val source = Intent(testContext, AudioSourceTestActivity::class.java).putExtra("onplayAudit", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            withTimeout(45000) { while (!vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge.isReady) delay(200) }
            container.settings.onPlayAudioEnabled = true
            testContext.startActivity(source)
            val begin = SystemClock.elapsedRealtime()
            var sessions = 0
            while (SystemClock.elapsedRealtime() - begin < 305000) {
                val id = withContext(Dispatchers.Main) {
                    val session = container.audioSessions.begin(testContext.packageName, context.packageManager.getPackageUid(testContext.packageName, 0), Language.AUTO, Language.VIETNAMESE)
                    context.startActivity(AudioCapturePermissionActivity.intent(context, session.id))
                    session.id
                }
                sessions++
                Log.i("OnplayAudit", "event=test_session session=$id at=${SystemClock.elapsedRealtime()}")
                val sessionStart = SystemClock.elapsedRealtime()
                do {
                    automation.rootInActiveWindow?.takeIf { it.packageName == "com.android.systemui" }?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    delay(500)
                } while (container.audioSessions.state.value?.id == id && SystemClock.elapsedRealtime() - begin < 305000 && SystemClock.elapsedRealtime() - sessionStart < 310000)
                if (SystemClock.elapsedRealtime() - begin < 305000) testContext.startActivity(source)
            }
            Log.i("OnplayAudit", "event=test_done elapsed=${SystemClock.elapsedRealtime()-begin} sessions=$sessions")
            Unit
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { AudioTranslationService.stop(context) }
            testContext.startActivity(Intent(source).setAction("STOP"))
            container.settings.onPlayAudioEnabled = oldOnplay
            container.settings.geminiLiveAudioEnabled = oldGemini
            container.settings.openAiRealtimeAudioEnabled = oldOpenai
        }
    }
}
