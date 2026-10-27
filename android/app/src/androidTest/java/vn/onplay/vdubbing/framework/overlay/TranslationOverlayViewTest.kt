package vn.onplay.vdubbing.framework.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TranslationOverlayViewTest {
    @Test fun translatedGlyphsRenderInHardwareAcceleratedWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val position = IntArray(2)
        val drawn = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = TranslationOverlayView(activity, listOf(
                    OverlayText(40, 100, 240, 70, "Bản dịch rõ ràng", 32f, 14f, false, Color.WHITE, 0f),
                ))
                activity.setContentView(view)
                view.viewTreeObserver.addOnDrawListener {
                    view.getLocationOnScreen(position)
                    view.post { drawn.countDown() }
                }
            }
            assertTrue("window must draw", drawn.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(it.window.decorView.isHardwareAccelerated) }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            var darkPixels = 0
            for (y in position[1] + 100 until position[1] + 170) {
                for (x in position[0] + 40 until position[0] + 280) {
                    if (screenshot.getPixel(x, y) == Color.BLACK) darkPixels++
                }
            }
            assertTrue("actual window must contain translated glyphs", darkPixels > 20)
            screenshot.recycle()
        }
    }

    @Test fun textRemainsInsideSmallBoxesOnFullScreenCanvas() {
        val bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.GRAY)
        val entries = listOf(
            OverlayText(80, 200, 200, 50, "Tên tác giả", 30f, 14f, false, Color.WHITE, 0f),
            OverlayText(550, 1500, 250, 70, "Đọc từ đầu", 32f, 14f, false, Color.BLACK, 0f),
        )
        entries.forEach { it.draw(canvas) }
        fun pixels(color: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
            var count = 0
            for (y in top until bottom) for (x in left until right) {
                if (bitmap.getPixel(x, y) == color) count++
            }
            return count
        }
        assertTrue("light box must contain black translated glyphs", pixels(Color.BLACK, 80, 200, 280, 250) > 20)
        assertTrue("dark box must contain white translated glyphs", pixels(Color.WHITE, 550, 1500, 800, 1570) > 20)
        assertEquals(Color.GRAY, bitmap.getPixel(500, 1000))
        bitmap.recycle()
    }

    @Test fun coloredMaskCoversOriginalAndDoesNotEscapeBox() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val green = 0xFF8BC34A.toInt()
            val bitmap = Bitmap.createBitmap(300, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.MAGENTA)
            val entry = OverlayText(30, 30, 240, 80, "Bắt đầu đọc truyện", 32f, 14f, false, green, 0f)
            val view = TranslationOverlayView(instrumentation.targetContext, listOf(entry))
            view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 300, 140)
            view.draw(canvas)
            assertEquals(green, bitmap.getPixel(30, 30))
            assertEquals(green, bitmap.getPixel(269, 109))
            assertEquals(Color.MAGENTA, bitmap.getPixel(29, 30))
            assertEquals(Color.MAGENTA, bitmap.getPixel(270, 109))
            var blackPixels = 0
            for (y in 30 until 110) for (x in 30 until 270) {
                if (bitmap.getPixel(x, y) == Color.BLACK) blackPixels++
            }
            assertTrue("dark text must render over a light button", blackPixels > 20)
            bitmap.recycle()
        }
    }

    @Test fun longTranslationShrinksAndWrapsWithinBox() {
        val entry = OverlayText(0, 0, 200, 100,
            "Bản dịch tiếng Việt dài hơn văn bản gốc", 50f, 10f, true, Color.WHITE, 0f)
        assertTrue(entry.layout.height <= 98)
        assertTrue(entry.layout.lineCount > 1)
        for (line in 0 until entry.layout.lineCount) {
            assertTrue(entry.layout.getLineWidth(line) <= 198)
            assertEquals(0, entry.layout.getEllipsisCount(line))
        }
    }

    @Test fun darkMaskUsesWhiteTextAndVeryLongTextIsClipped() {
        val bitmap = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        val entry = OverlayText(0, 0, 100, 40, "Nội dung rất dài ".repeat(40), 36f, 14f, false, Color.BLACK, 0f)
        entry.draw(Canvas(bitmap))
        assertTrue(entry.layout.getEllipsisCount(entry.layout.lineCount - 1) > 0)
        var whitePixels = 0
        for (y in 0 until 40) for (x in 0 until 100) {
            if (bitmap.getPixel(x, y) == Color.WHITE) whitePixels++
        }
        assertTrue("white text must render over a dark background", whitePixels > 10)
        bitmap.recycle()
    }
}
