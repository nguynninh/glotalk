package vn.onplay.vdubbing.presentation.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language

class VoiceInputTest {
    @Test fun recognitionUsesTheSelectedSourceLanguage() {
        assertEquals("vi-VN", speechLanguageTag(Language.VIETNAMESE))
        assertEquals("en-US", speechLanguageTag(Language.ENGLISH))
        assertEquals("ja-JP", speechLanguageTag(Language.JAPANESE))
        assertEquals("ko-KR", speechLanguageTag(Language.KOREAN))
        assertEquals("zh-CN", speechLanguageTag(Language.CHINESE))
    }

    @Test fun recognitionFailuresGiveActionableMessages() {
        val messages = mapOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to R.string.voice_permission_required,
            SpeechRecognizer.ERROR_NO_MATCH to R.string.voice_no_match,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to R.string.voice_no_match,
            SpeechRecognizer.ERROR_NETWORK to R.string.voice_network_error,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to R.string.voice_network_error,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to R.string.voice_language_unavailable,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to R.string.voice_language_unavailable,
            SpeechRecognizer.ERROR_AUDIO to R.string.voice_failed,
        )
        messages.forEach { (error, message) -> assertEquals(message, voiceErrorMessage(error)) }
    }
}
