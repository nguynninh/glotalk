package vn.onplay.vdubbing.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Script
import vn.onplay.vdubbing.domain.model.TextBlock
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationException
import vn.onplay.vdubbing.domain.repository.CapturedScreen
import vn.onplay.vdubbing.domain.repository.CloudTranslationRepository
import vn.onplay.vdubbing.domain.repository.ConnectivityRepository
import vn.onplay.vdubbing.domain.repository.OcrRepository
import vn.onplay.vdubbing.domain.repository.ScreenCaptureRepository
import vn.onplay.vdubbing.domain.repository.ScreenSize
import vn.onplay.vdubbing.domain.repository.TranslationRepository

class TranslateScreenUseCaseTest {

    // --- fakes -------------------------------------------------------------

    private class FakeCapture(
        var ready: Boolean = true,
    ) : ScreenCaptureRepository {
        val overlays = mutableListOf<List<TextBlock>>()
        val released = mutableListOf<CapturedScreen>()

        override fun isReady() = ready
        override suspend fun capture() = CapturedScreen("test")
        override fun lastScreenSize() = ScreenSize(1080, 1920)
        override fun release(screen: CapturedScreen) {
            released += screen
        }
        var foreground: String? = "com.other.app"
        override fun foregroundPackage(): String? = foreground
        override fun showOverlay(blocks: List<TextBlock>, at: ScreenSize) {
            overlays.add(blocks)
        }
    }

    private class FakeOcr(private val blocks: List<TextBlock>) : OcrRepository {
        override suspend fun recognize(screen: CapturedScreen, script: Script) = blocks
    }

    private class FakeTranslator(
        private val detected: Language = Language.ENGLISH,
    ) : TranslationRepository {
        override suspend fun detect(text: String) = detected
        override suspend fun translate(lines: List<String>, source: Language, target: Language) =
            lines.map { "[${target.label}] $it" }
    }

    private class FakeCloud(
        private val configured: Boolean = true,
        private val failure: Throwable? = null,
    ) : CloudTranslationRepository {
        var calls = 0
        override fun isConfigured() = configured
        override suspend fun translate(
            screen: CapturedScreen,
            source: Language,
            target: Language,
        ): Translation {
            calls++
            failure?.let { throw it }
            return Translation(
                blocks = listOf(block("cloud result")),
                engine = TranslationEngine.CLOUD,
                source = source,
                target = target,
            )
        }

        override suspend fun verifyKey(key: String) = Unit
    }

    private class FakeConnectivity(private val online: Boolean = true) : ConnectivityRepository {
        override fun isOnline() = online
    }

    private fun useCase(
        capture: FakeCapture = FakeCapture(),
        ocr: OcrRepository,
        translator: TranslationRepository = FakeTranslator(),
        cloud: CloudTranslationRepository = FakeCloud(),
        connectivity: ConnectivityRepository = FakeConnectivity(),
    ) = TranslateScreenUseCase(capture, ocr, translator, cloud, connectivity)

    // --- tests -------------------------------------------------------------

    @Test(expected = TranslationException.AccessibilityDisabled::class)
    fun `fails before touching the screen when accessibility is off`() = runTest {
        val cloud = FakeCloud()
        useCase(capture = FakeCapture(ready = false), ocr = FakeOcr(emptyList()), cloud = cloud)
            .invoke(Language.AUTO, Language.VIETNAMESE)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `good local OCR stays on device and never calls the cloud`() = runTest {
        val cloud = FakeCloud()
        val result = useCase(
            ocr = FakeOcr(listOf(block("hello world, this is a long line"), block("second line here"))),
            cloud = cloud,
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(TranslationEngine.ON_DEVICE, result.engine)
        assertEquals(0, cloud.calls)
        assertTrue(result.blocks.all { it.translated.startsWith("[${Language.VIETNAMESE.label}]") })
    }

    @Test
    fun `poor local OCR falls back to cloud with bounding boxes intact`() = runTest {
        val cloud = FakeCloud()
        val result = useCase(
            ocr = FakeOcr(listOf(block("x", confidence = 0.1))),
            cloud = cloud,
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(TranslationEngine.CLOUD, result.engine)
        assertEquals(1, cloud.calls)
    }

    @Test
    fun `null confidence is not treated as zero confidence`() = runTest {
        val cloud = FakeCloud()
        val result = useCase(
            // Scripts that never report confidence must still reach the local path.
            ocr = FakeOcr(listOf(block("a sufficiently long run of on-screen text", null))),
            cloud = cloud,
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(TranslationEngine.ON_DEVICE, result.engine)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `cloud is skipped when offline and local text still returned`() = runTest {
        val cloud = FakeCloud()
        val result = useCase(
            ocr = FakeOcr(listOf(block("noisy", confidence = 0.1))),
            cloud = cloud,
            connectivity = FakeConnectivity(online = false),
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(TranslationEngine.ON_DEVICE, result.engine)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `cloud is skipped when not configured`() = runTest {
        val cloud = FakeCloud(configured = false)
        val result = useCase(
            ocr = FakeOcr(listOf(block("noisy", confidence = 0.1))),
            cloud = cloud,
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(TranslationEngine.ON_DEVICE, result.engine)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `transient cloud failure degrades to local result`() = runTest {
        val cloud = FakeCloud(failure = TranslationException.Offline())
        val result = useCase(
            ocr = FakeOcr(listOf(block("noisy", confidence = 0.1))),
            cloud = cloud,
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(TranslationEngine.ON_DEVICE, result.engine)
        assertEquals(1, cloud.calls)
    }

    @Test(expected = TranslationException.InvalidApiKey::class)
    fun `bad api key surfaces to the caller instead of silently degrading`() = runTest {
        useCase(
            ocr = FakeOcr(listOf(block("noisy", confidence = 0.1))),
            cloud = FakeCloud(failure = TranslationException.InvalidApiKey()),
        ).invoke(Language.AUTO, Language.VIETNAMESE)
    }

    @Test(expected = TranslationException.NoTextFound::class)
    fun `blank screen reports no text rather than an empty translation`() = runTest {
        useCase(
            ocr = FakeOcr(emptyList()),
            cloud = FakeCloud(configured = false),
        ).invoke(Language.AUTO, Language.VIETNAMESE)
    }

    @Test
    fun `no overlay when our own settings screen is in front`() = runTest {
        val capture = FakeCapture()
        capture.foreground = "vn.onplay.vdubbing"

        val result = useCase(
            capture = capture,
            ocr = FakeOcr(listOf(block("a sufficiently long run of on-screen text"))),
        ).invoke(Language.AUTO, Language.VIETNAMESE, ownPackage = "vn.onplay.vdubbing")

        assertEquals(1, result.blocks.size)
        assertTrue(capture.overlays.isEmpty())
    }

    @Test
    fun `overlay is drawn when another app is in front`() = runTest {
        val capture = FakeCapture()
        capture.foreground = "com.other.app"

        useCase(
            capture = capture,
            ocr = FakeOcr(listOf(block("a sufficiently long run of on-screen text"))),
        ).invoke(Language.AUTO, Language.VIETNAMESE, ownPackage = "vn.onplay.vdubbing")

        assertEquals(1, capture.overlays.size)
    }

    @Test
    fun `same source and target reports text without drawing an overlay`() = runTest {
        val capture = FakeCapture()

        useCase(
            capture = capture,
            ocr = FakeOcr(listOf(block("a sufficiently long run of on-screen text"))),
        ).invoke(Language.VIETNAMESE, Language.VIETNAMESE)

        assertTrue(capture.overlays.isEmpty())
    }

    @Test
    fun `unknown detection falls back to the OCR language`() = runTest {
        val result = useCase(
            ocr = FakeOcr(listOf(block("a sufficiently long run of on-screen text"))),
            translator = FakeTranslator(detected = Language.UNKNOWN),
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        assertEquals(Language.ENGLISH, result.source)
    }

    @Test
    fun `unknown detection uses English only when OCR cannot name a source`() = runTest {
        val result = useCase(
            ocr = FakeOcr(listOf(block("a sufficiently long run of text").copy(language = Language.UNKNOWN))),
            translator = FakeTranslator(detected = Language.UNKNOWN),
        ).invoke(Language.AUTO, Language.VIETNAMESE)
        assertEquals(Language.ENGLISH, result.source)
    }

    @Test
    fun `unknown detection preserves a non English OCR source`() = runTest {
        val result = useCase(
            ocr = FakeOcr(listOf(block("a sufficiently long run of text").copy(language = Language.JAPANESE))),
            translator = FakeTranslator(detected = Language.UNKNOWN),
        ).invoke(Language.AUTO, Language.VIETNAMESE)
        assertEquals(Language.JAPANESE, result.source)
    }

    @Test
    fun `overlapping callers cannot capture until the active request releases its screen`() = runTest {
        val capture = FakeCapture()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val pipeline = useCase(capture = capture, ocr = object : OcrRepository {
            override suspend fun recognize(screen: CapturedScreen, script: Script): List<TextBlock> {
                entered.complete(Unit)
                finish.await()
                return listOf(block("a sufficiently long run of text"))
            }
        })
        val first = async { pipeline(Language.ENGLISH, Language.VIETNAMESE) }
        entered.await()
        try {
            pipeline(Language.JAPANESE, Language.KOREAN)
            throw AssertionError("Expected Busy")
        } catch (_: TranslationException.Busy) {
            assertTrue(capture.released.isEmpty())
        }
        finish.complete(Unit)
        first.await()
        assertEquals(1, capture.released.size)
        pipeline(Language.ENGLISH, Language.VIETNAMESE)
        assertEquals(2, capture.released.size)
    }

    @Test
    fun `cancelling OCR releases the capture without cloud fallback or overlay and unlocks the pipeline`() = runTest {
        val capture = FakeCapture()
        val cloud = FakeCloud()
        val entered = CompletableDeferred<Unit>()
        var wait = true
        val pipeline = useCase(capture = capture, cloud = cloud, ocr = object : OcrRepository {
            override suspend fun recognize(screen: CapturedScreen, script: Script): List<TextBlock> {
                if (wait) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                return listOf(block("a sufficiently long run of text"))
            }
        })
        val first = launch { pipeline(Language.AUTO, Language.VIETNAMESE) }
        entered.await()
        first.cancelAndJoin()
        assertEquals(1, capture.released.size)
        assertEquals(0, cloud.calls)
        assertTrue(capture.overlays.isEmpty())
        wait = false
        pipeline(Language.AUTO, Language.VIETNAMESE)
        assertEquals(2, capture.released.size)
    }

    @Test
    fun `a page already in the target language is not translated`() = runTest {
        val capture = FakeCapture()
        var asked = 0
        val translator = object : TranslationRepository {
            override suspend fun detect(text: String) = Language.VIETNAMESE
            override suspend fun translate(
                lines: List<String>,
                source: Language,
                target: Language,
            ): List<String> {
                asked++
                return lines
            }
        }

        val result = useCase(
            capture = capture,
            ocr = FakeOcr(listOf(block("Cũng Xuyên Khê Như Bảo"))),
            translator = translator,
        ).invoke(Language.AUTO, Language.VIETNAMESE)

        // Running Vietnamese through the translator anyway is what produced
        // "Cừng Dễu" over words that were already correct.
        assertEquals(0, asked)
        assertEquals(Language.VIETNAMESE, result.source)
        assertEquals("Cũng Xuyên Khê Như Bảo", result.blocks.single().translated)
    }
}

/** Nested fakes cannot see outer members, so this stays top-level. */
private fun block(text: String, confidence: Double? = 0.9) = TextBlock(
    original = text,
    translated = text,
    language = Language.ENGLISH,
    left = 0,
    top = 0,
    width = 100,
    height = 20,
    confidence = confidence,
)
