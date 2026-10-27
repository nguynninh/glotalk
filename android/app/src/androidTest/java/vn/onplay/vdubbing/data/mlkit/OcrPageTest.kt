package vn.onplay.vdubbing.data.mlkit

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.data.capture.BitmapCaptureStore
import vn.onplay.vdubbing.domain.model.Script
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.framework.overlay.OverlayText
import vn.onplay.vdubbing.framework.overlay.shouldDrawTranslation
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrPageTest {
    @Test fun readsVietnamesePageWithoutDroppingLeadingLetters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("translator-page.png").use(BitmapFactory::decodeStream)
        val store = BitmapCaptureStore()
        val screen = store.put(bitmap)
        val ocr = OcrRepositoryImpl(store)
        try {
            val blocks = ocr.recognize(screen, Script.LATIN)
            assertTrue("a page full of visible text must not produce an empty OCR result", blocks.isNotEmpty())
            blocks.forEach { Log.i("VDubbingFixture", "${it.original} [${it.left},${it.top},${it.width},${it.height}]") }
            val title = blocks.first { it.top in 270..330 && it.original.startsWith("Đồ") }
            assertTrue("the mask must cover the entire leading Đ", title.left <= 80)
            assertTrue("the title must not be split at a table column below it", title.original.contains("Phản"))
            assertTrue(blocks.any { it.original == "Tác giả" })
            assertTrue(blocks.any { it.original == "Tên khác" })
            assertTrue(blocks.any { it.original == "Ngày tạo" })
            assertTrue(blocks.any { it.original == "Nhóm dịch" })
            assertTrue(blocks.any { it.original == "Mộng Tiên Giới" })
            assertTrue(blocks.none { it.original.contains("Nhóm dịch Mộng") })
            assertTrue(blocks.any { it.original == "572" })
            assertTrue(blocks.any { it.original == "60,480" })
            assertTrue(blocks.any { it.original == "Chuyển Sinh" })
            assertTrue(blocks.first { it.original.contains("thích") }.left <= 137)
            val translator = TranslationRepositoryImpl()
            try {
                val source = translator.detect(blocks.joinToString(" ") { it.original })
                assertEquals(Language.VIETNAMESE, source)
                val translated = translator.translate(blocks.map { it.original }, source, Language.CHINESE)
                val preview = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(preview)
                blocks.mapIndexed { index, block -> block.copy(translated = translated[index]) }
                    .filter(::shouldDrawTranslation).forEach { block ->
                        val h = block.height + 6
                        OverlayText(block.left - 3, block.top - 3, block.width + 6, h, block.text,
                            (block.height * 0.85f / block.lineCount).coerceIn(30f, 102f),
                            minOf(30f, h * 0.6f), block.lineCount > 1, block.backgroundColor, 0f).draw(canvas)
                    }
                File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ocr-overlay-preview.png")
                    .outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                preview.recycle()
            } finally { translator.close() }
        } finally {
            ocr.close()
            store.release(screen)
        }
    }
}
