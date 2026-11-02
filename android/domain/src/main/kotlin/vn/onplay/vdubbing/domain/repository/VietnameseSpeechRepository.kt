package vn.onplay.vdubbing.domain.repository

interface VietnameseSpeechRepository {
    suspend fun speak(text: String, onProgress: (Float) -> Unit, onReady: () -> Unit)
    suspend fun close()
}
