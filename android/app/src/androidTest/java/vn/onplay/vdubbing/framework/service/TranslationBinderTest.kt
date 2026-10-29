package vn.onplay.vdubbing.framework.service

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import vn.onplay.vdubbing.ITranslationCallback
import vn.onplay.vdubbing.domain.model.*
import vn.onplay.vdubbing.domain.repository.SettingsRepository

@RunWith(AndroidJUnit4::class)
class TranslationBinderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = object : SettingsRepository {
        override var geminiLiveKey = ""
            override var geminiLiveAudioEnabled = false
            override var geminiKey = ""
        override var cloudFallbackEnabled = false
        override var sourceLanguage = Language.ENGLISH
        override var targetLanguage = Language.VIETNAMESE
        override var displayMode = DisplayMode.REPLACE
        override var autoTranslate = false
        override var themeMode = ThemeMode.SYSTEM
        override var appLanguage = AppLanguage.SYSTEM
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun workerCallerUsesExplicitLanguagesOnMainWithoutOverwritingSettings() {
        val done = CountDownLatch(1)
        val json = AtomicReference<String>()
        val calls = AtomicInteger()
        val binder = binder { source, target ->
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            calls.incrementAndGet()
            Translation(emptyList(), TranslationEngine.ON_DEVICE, source, target)
        }
        Thread { binder.translateNow("ja-JP", "ko", callback(done, json)) }.apply { start(); join(1000) }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertEquals("ja", JSONObject(json.get()).getString("sourceLang"))
        assertEquals("ko", JSONObject(json.get()).getString("targetLang"))
        assertEquals(Language.ENGLISH, settings.sourceLanguage)
        assertEquals(Language.VIETNAMESE, settings.targetLanguage)
        assertEquals("previous-result", binder.getLastResult())
        assertTrue(binder.isAccessibilityReady())
    }

    @Test
    fun invalidLanguagesFallBackAndFailureIsDeliveredOnceWithStableCode() {
        val done = CountDownLatch(1)
        val errors = AtomicInteger()
        val binder = binder { source, target ->
            assertEquals(Language.ENGLISH, source)
            assertEquals(Language.VIETNAMESE, target)
            throw TranslationException.Busy()
        }
        Thread {
            binder.translateNow("invalid", "auto", object : ITranslationCallback.Stub() {
                override fun onResult(json: String?) = fail("Expected a failure")
                override fun onError(code: Int, message: String?) {
                    assertEquals(8, code)
                    errors.incrementAndGet()
                    done.countDown()
                }
            })
        }.apply { start(); join(1000) }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, errors.get())
    }

    @Test
    fun autoModeAndOverlayCommandsFromWorkerRunOnMain() {
        val done = CountDownLatch(2)
        val binder = TranslationBinder(scope, settings, runner { _, _ -> error("Must not translate") }, { "" }, { true }, {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            done.countDown()
        }, { enabled ->
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertTrue(enabled)
            assertEquals(Language.JAPANESE, settings.sourceLanguage)
            assertEquals(Language.KOREAN, settings.targetLanguage)
            done.countDown()
        })
        Thread {
            binder.setAutoTranslate(true, "ja", "ko")
            binder.hideOverlay()
        }.apply { start(); join(1000) }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun runner(execute: suspend (Language, Language) -> Translation) =
        TranslationRequestRunner(scope, execute, {}, {}, {})

    private fun binder(execute: suspend (Language, Language) -> Translation) =
        TranslationBinder(scope, settings, runner(execute), { "previous-result" }, { true }, {}, {})

    private fun callback(done: CountDownLatch, json: AtomicReference<String>) = object : ITranslationCallback.Stub() {
        override fun onResult(result: String?) {
            json.set(result)
            done.countDown()
        }
        override fun onError(code: Int, message: String?) = fail("Unexpected error $code: $message")
    }
}
