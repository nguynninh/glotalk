package vn.onplay.vdubbing.presentation.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.onplay.vdubbing.domain.model.*
import vn.onplay.vdubbing.domain.repository.*
import vn.onplay.vdubbing.domain.usecase.TranslateScreenUseCase
import vn.onplay.vdubbing.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `resume reloads settings changed by overlay or AIDL`() = runTest(dispatcher) {
        val fixture = Fixture()
        val model = fixture.model()
        runCurrent()
        fixture.settings.apply {
            sourceLanguage = Language.JAPANESE
            targetLanguage = Language.KOREAN
            displayMode = DisplayMode.PARALLEL
            autoTranslate = true
            themeMode = ThemeMode.DARK
            appLanguage = AppLanguage.ENGLISH
            geminiKey = "updated-key"
            cloudFallbackEnabled = true
        }
        model.refreshSystemState()
        with(model.state.value) {
            assertEquals(Language.JAPANESE, sourceLanguage)
            assertEquals(Language.KOREAN, targetLanguage)
            assertEquals(DisplayMode.PARALLEL, displayMode)
            assertTrue(autoTranslate)
            assertEquals(ThemeMode.DARK, themeMode)
            assertEquals(AppLanguage.ENGLISH, appLanguage)
            assertEquals("updated-key", savedKey)
            assertTrue(cloudEnabled)
        }
    }

    @Test
    fun `translation reads current repository values even before UI refresh`() = runTest(dispatcher) {
        val fixture = Fixture()
        val model = fixture.model()
        fixture.settings.sourceLanguage = Language.JAPANESE
        fixture.settings.targetLanguage = Language.KOREAN
        model.onTranslateNow()
        runCurrent()
        assertEquals(Language.JAPANESE to Language.KOREAN, fixture.translatedPair)
        assertEquals(Language.KOREAN, model.state.value.lastTranslation?.target)
        assertFalse(model.state.value.translating)
    }

    @Test
    fun `service status does not overwrite persisted auto setting and is isolated per container`() = runTest(dispatcher) {
        val first = Fixture()
        first.settings.autoTranslate = true
        val model = first.model()
        val secondModel = Fixture().model()
        runCurrent()
        assertTrue(model.state.value.autoTranslate)
        first.backgroundState.value = BackgroundState(running = true, autoTranslate = true)
        runCurrent()
        assertTrue(model.state.value.serviceRunning)
        assertFalse(secondModel.state.value.serviceRunning)
        model.onAutoTranslateChange(false)
        assertFalse(first.settings.autoTranslate)
        assertEquals(1, first.autoUpdates)
    }

    private class Fixture {
        val settings = object : SettingsRepository {
            override var geminiKey = ""
            override var cloudFallbackEnabled = false
            override var sourceLanguage = Language.AUTO
            override var targetLanguage = Language.VIETNAMESE
            override var displayMode = DisplayMode.REPLACE
            override var autoTranslate = false
            override var themeMode = ThemeMode.SYSTEM
            override var appLanguage = AppLanguage.SYSTEM
        }
        val backgroundState = MutableStateFlow(BackgroundState())
        val background = object : BackgroundStatusRepository {
            override val state = backgroundState
        }
        var autoUpdates = 0
        var translatedPair: Pair<Language, Language>? = null
        private val cloud = object : CloudTranslationRepository {
            override fun isConfigured() = false
            override suspend fun verifyKey(key: String) = Unit
            override suspend fun translate(screen: CapturedScreen, source: Language, target: Language): Translation =
                error("Cloud should not be used")
        }
        private val capture = object : ScreenCaptureRepository {
            override fun isReady() = true
            override suspend fun capture() = CapturedScreen("screen")
            override fun lastScreenSize() = ScreenSize(100, 100)
            override fun release(screen: CapturedScreen) = Unit
            override fun foregroundPackage() = "app"
            override fun showOverlay(blocks: List<TextBlock>, at: ScreenSize) = error("Own screen must not be overlaid")
        }
        private val ocr = object : OcrRepository {
            override suspend fun recognize(screen: CapturedScreen, script: Script) = listOf(
                TextBlock("a sufficiently long run of text", "", Language.ENGLISH, 0, 0, 100, 10),
            )
        }
        private val translator = object : TranslationRepository {
            override suspend fun detect(text: String) = Language.ENGLISH
            override suspend fun translate(lines: List<String>, source: Language, target: Language): List<String> {
                translatedPair = source to target
                return lines.map { "translated" }
            }
        }
        private val services = object : ServiceController {
            override fun start() = Unit
            override fun stop() = Unit
            override fun hideOverlay() = Unit
            override fun showBubble(onTranslate: () -> Unit) = Unit
            override fun setDisplayMode(mode: DisplayMode) = Unit
            override fun applyAutoTranslate() { autoUpdates++ }
        }
        private val accessibility = object : AccessibilityStatus {
            override fun isLive() = true
            override fun isEnabledInSystemSettings() = true
            override fun diagnose() = AccessibilityStatus.Snapshot(true, true, "service", "service", true)
            override fun openSystemSettings() = Unit
        }
        fun model() = SettingsViewModel(
            settings, cloud, TranslateScreenUseCase(capture, ocr, translator, cloud, object : ConnectivityRepository {
                override fun isOnline() = true
            }), services, object : PermissionChecker {
                override fun notificationsGranted() = true
            }, object : AppEnvironment {
                override val packageName = "app"
            }, accessibility, background,
        )
    }
}
