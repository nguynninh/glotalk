package vn.onplay.vdubbing.framework.overlay

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.R

@RunWith(AndroidJUnit4::class)
class AudioBubbleTest {
    @Test fun existingPanelStartsAndStopsAudioWithoutStartingScreenTranslation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val service = object : AccessibilityService() {
                init { attachBaseContext(instrumentation.targetContext) }
                override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
                override fun onInterrupt() = Unit
            }
            val renderer = AccessibilityOverlayRenderer(service)
            var state = PanelState(audioStatusRes = R.string.audio_listening)
            val toggles = mutableListOf<Boolean>()
            var screenCalls = 0
            renderer.host = Proxy.newProxyInstance(OverlayHost::class.java.classLoader,
                arrayOf(OverlayHost::class.java)) { _, method, args ->
                when (method.name) {
                    "panelState" -> state
                    "onAudioTranslation" -> { toggles.add(args!![0] as Boolean); null }
                    "onStartTranslation" -> { screenCalls++; null }
                    else -> null
                }
            } as OverlayHost
            val build = renderer.javaClass.getDeclaredMethod("buildPanelCard", PanelState::class.java)
                .apply { isAccessible = true }
            fun toggle(): Switch = descendants(build.invoke(renderer, state) as View)
                .filterIsInstance<Switch>()
                .single { it.contentDescription == instrumentation.targetContext.getString(R.string.audio_title) }
            toggle().apply { assertFalse(isChecked); performClick() }
            state = state.copy(audioActive = true)
            toggle().apply { assertTrue(isChecked); performClick() }
            assertEquals(listOf(true, false), toggles)
            assertEquals(0, screenCalls)
        }
    }

    @Test fun volumeSlidersDispatchIndependentChangesWhileDragging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originals = mutableListOf<Int>()
        val translations = mutableListOf<Int>()
        ActivityScenario.launch(OverlayTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = Proxy.newProxyInstance(OverlayHost::class.java.classLoader,
                    arrayOf(OverlayHost::class.java)) { _, method, args ->
                    when (method.name) {
                        "onOriginalVolume" -> { originals.add(args!![0] as Int); null }
                        "onTranslationVolume" -> { translations.add(args!![0] as Int); null }
                        else -> null
                    }
                } as OverlayHost
                activity.setContentView(OverlayPanelContent(activity, OverlayUi(activity),
                    { host }, {}, {}, {}).build(PanelState(originalVolume = 80, translationVolume = 80)))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            fun drag(label: Int) = scenario.onActivity { activity ->
                val bar = descendants(activity.window.decorView).filterIsInstance<SeekBar>()
                    .single { it.contentDescription == context.getString(label) }
                val at = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN to 0.8f, MotionEvent.ACTION_MOVE to 0.1f,
                    MotionEvent.ACTION_UP to 0.1f).forEachIndexed { index, (action, position) ->
                    val event = MotionEvent.obtain(at, at + index * 10L, action,
                        bar.width * position, bar.height / 2f, 0)
                    bar.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            drag(R.string.audio_original_volume)
            assertTrue(originals.isNotEmpty())
            assertTrue(originals.last() < 30)
            assertTrue(translations.isEmpty())
            drag(R.string.audio_translation_volume)
            assertTrue(translations.isNotEmpty())
            assertTrue(translations.last() < 30)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
