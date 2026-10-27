package vn.onplay.vdubbing.framework.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.MainActivity
import vn.onplay.vdubbing.domain.model.Language

@RunWith(AndroidJUnit4::class)
class LanguageDropdownTest {
    @Test fun dropdownTogglesSwitchesAndClosesAfterSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var state = PanelState()
        lateinit var wrapper: FrameLayout
        fun card() = wrapper.getChildAt(0) as LinearLayout
        fun chip(index: Int) = (card().getChildAt(2) as LinearLayout).getChildAt(index)
        fun dropdown() = (0 until card().childCount)
            .map { card().getChildAt(it) }.filterIsInstance<ScrollView>().singleOrNull()
        fun options() = dropdown()!!.getChildAt(0) as LinearLayout

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val service = object : AccessibilityService() {
                    init { attachBaseContext(activity) }
                    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
                    override fun onInterrupt() = Unit
                }
                val renderer = AccessibilityOverlayRenderer(service)
                renderer.host = Proxy.newProxyInstance(
                    OverlayHost::class.java.classLoader,
                    arrayOf(OverlayHost::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "panelState" -> state
                        "onSourceLanguage" -> { state = state.copy(source = args!![0] as Language); null }
                        "onTargetLanguage" -> { state = state.copy(target = args!![0] as Language); null }
                        else -> null
                    }
                } as OverlayHost
                val build = renderer.javaClass.getDeclaredMethod("buildPanelCard", PanelState::class.java)
                    .apply { isAccessible = true }
                wrapper = FrameLayout(activity).apply {
                    addView(build.invoke(renderer, state) as View)
                }
                val root = FrameLayout(activity).apply { addView(wrapper) }
                renderer.javaClass.getDeclaredField("panel").apply { isAccessible = true }
                    .set(renderer, root)
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            fun tap(findView: () -> View) {
                val bounds = Rect()
                scenario.onActivity {
                    assertTrue("tap target must be visible", findView().getGlobalVisibleRect(bounds))
                }
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
                instrumentation.waitForIdleSync()
            }

            scenario.onActivity { assertNull(dropdown()) }
            repeat(3) {
                tap { chip(0) }
                scenario.onActivity {
                    assertEquals(1, (0 until card().childCount)
                        .count { card().getChildAt(it) is ScrollView })
                    assertEquals(LinearLayout.VERTICAL, options().orientation)
                    assertEquals(Language.SOURCES.size, options().childCount)
                    assertTrue(options().getChildAt(0).isSelected)
                    val start = card().getChildAt(card().childCount - 1) as TextView
                    val visible = Rect()
                    assertTrue(start.getGlobalVisibleRect(visible))
                    assertEquals("start button must remain fully visible", start.height, visible.height())
                }
                tap { chip(0) }
                scenario.onActivity { assertNull(dropdown()) }
            }
            tap { chip(0) }
            tap { chip(2) }
            scenario.onActivity { assertEquals(Language.TARGETS.size, options().childCount) }
            tap { options().getChildAt(Language.TARGETS.indexOf(Language.ENGLISH)) }
            scenario.onActivity {
                assertEquals(Language.ENGLISH, state.target)
                assertEquals(Language.AUTO, state.source)
                assertNull(dropdown())
            }
            tap { chip(0) }
            tap { options().getChildAt(Language.SOURCES.indexOf(Language.VIETNAMESE)) }
            scenario.onActivity {
                assertEquals(Language.VIETNAMESE, state.source)
                assertNull(dropdown())
            }
            tap { chip(0) }
            scenario.onActivity {
                assertTrue(options().getChildAt(Language.SOURCES.indexOf(Language.VIETNAMESE)).isSelected)
                dropdown()!!.apply {
                    isSmoothScrollingEnabled = false
                    fullScroll(View.FOCUS_DOWN)
                }
            }
            instrumentation.waitForIdleSync()
            tap { options().getChildAt(Language.SOURCES.indexOf(Language.CHINESE)) }
            scenario.onActivity {
                assertEquals(Language.CHINESE, state.source)
                assertNull(dropdown())
            }
        }
    }
}
