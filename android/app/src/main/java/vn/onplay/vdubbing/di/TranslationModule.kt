package vn.onplay.vdubbing.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository
import vn.onplay.vdubbing.domain.repository.TranslationRepository

@Module
@InstallIn(SingletonComponent::class)
object TranslationModule {
    @Provides
    fun history(container: AppContainer): TranslationHistoryRepository = container.history

    @Provides
    fun translation(container: AppContainer): TranslationRepository = container.translation
}
