package vn.onplay.vdubbing.framework.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TextBlock

@RunWith(AndroidJUnit4::class)
class OverlayWindowOrderTest {
    @Test fun dismissBubbleStaysAboveTranslationAfterEveryRedraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val windows = mutableListOf<View>()
            val layouts = mutableMapOf<View, WindowManager.LayoutParams>()
            val realManager = instrumentation.targetContext.getSystemService(Context.WINDOW_SERVICE)
            val manager = Proxy.newProxyInstance(
                WindowManager::class.java.classLoader,
                arrayOf(WindowManager::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "addView" -> {
                        val view = args!![0] as View
                        check(view !in windows)
                        windows.add(view)
                        layouts[view] = args[1] as WindowManager.LayoutParams
                    }
                    "removeView", "removeViewImmediate" -> check(windows.remove(args!![0]))
                    else -> return@newProxyInstance method.invoke(realManager, *(args ?: emptyArray()))
                }
                null
            } as WindowManager
            val service = object : AccessibilityService() {
                init { attachBaseContext(instrumentation.targetContext) }
                override fun getSystemService(name: String): Any? =
                    if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
                override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
                override fun onInterrupt() = Unit
            }
            val renderer = AccessibilityOverlayRenderer(service)
            renderer.showBubble {}
            val bubble = windows.single()
            val position = layouts.getValue(bubble).let { it.x to it.y }
            val blocks = listOf(TextBlock("Hello", "Xin chào", Language.ENGLISH, 16, 132, 200, 60))
            fun assertBubbleOnTop() {
                assertEquals(2, windows.size)
                assertSame(bubble, windows.last())
                assertEquals(position, layouts.getValue(bubble).let { it.x to it.y })
            }
            renderer.show(blocks, 1080, 2340)
            assertBubbleOnTop()
            renderer.setDisplayMode(DisplayMode.PARALLEL)
            assertBubbleOnTop()
            renderer.refresh()
            assertBubbleOnTop()
            renderer.show(blocks, 1080, 2340)
            assertBubbleOnTop()
            renderer.hide()
            assertSame(bubble, windows.single())
            renderer.hideBubble()
            renderer.show(blocks, 1080, 2340)
            assertEquals(1, windows.size)
            renderer.hide()
            assertEquals(0, windows.size)
        }
    }
}
