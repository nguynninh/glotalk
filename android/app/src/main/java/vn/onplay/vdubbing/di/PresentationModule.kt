package vn.onplay.vdubbing.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import vn.onplay.vdubbing.VDubbingApp
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.repository.BackgroundStatusRepository
import vn.onplay.vdubbing.domain.repository.CloudTranslationRepository
import vn.onplay.vdubbing.domain.repository.SettingsRepository
import vn.onplay.vdubbing.domain.usecase.TranslateScreenUseCase
import vn.onplay.vdubbing.domain.time.Countdown
import vn.onplay.vdubbing.framework.accessibility.AccessibilityBridge
import vn.onplay.vdubbing.framework.accessibility.SystemAccessibilityStatus
import vn.onplay.vdubbing.framework.service.TranslateService
import vn.onplay.vdubbing.framework.time.AndroidCountdown
import vn.onplay.vdubbing.presentation.AccessibilityStatus
import vn.onplay.vdubbing.presentation.AppEnvironment
import vn.onplay.vdubbing.presentation.PermissionChecker
import vn.onplay.vdubbing.presentation.ServiceController

@Module
@InstallIn(SingletonComponent::class)
object PresentationModule {
    @Provides
    fun countdown(): Countdown = AndroidCountdown()

    @Provides
    fun container(@ApplicationContext context: Context): AppContainer = (context as VDubbingApp).container

    @Provides
    fun backgroundStatus(container: AppContainer): BackgroundStatusRepository = container.backgroundStatus

    @Provides
    fun settings(container: AppContainer): SettingsRepository = container.settings

    @Provides
    fun cloud(container: AppContainer): CloudTranslationRepository = container.gemini

    @Provides
    fun translateScreen(container: AppContainer): TranslateScreenUseCase = container.translateScreen

    @Provides
    fun accessibility(@ApplicationContext context: Context): AccessibilityStatus = SystemAccessibilityStatus(context)

    @Provides
    fun environment(@ApplicationContext context: Context): AppEnvironment = object : AppEnvironment {
        override val packageName: String get() = context.packageName
    }

    @Provides
    fun permissions(@ApplicationContext context: Context): PermissionChecker = object : PermissionChecker {
        override fun notificationsGranted(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @Provides
    @Singleton
    fun services(@ApplicationContext context: Context): ServiceController = object : ServiceController {
        override fun start() = TranslateService.start(context)
        override fun stopAudioTranslation() = vn.onplay.vdubbing.framework.audio.AudioTranslationService.stop(context)
        override fun stop() = TranslateService.stop(context)
        override fun hideOverlay() { AccessibilityBridge.service?.overlayRenderer?.hide() }
        override fun showBubble(onTranslate: () -> Unit) {
            AccessibilityBridge.service?.overlayRenderer?.showBubble(onTranslate)
        }
        override fun setDisplayMode(mode: DisplayMode) { AccessibilityBridge.service?.overlayRenderer?.setDisplayMode(mode) }
        override fun applyAutoTranslate() { TranslateService.applyAutoTranslate(context) }
    }
}
