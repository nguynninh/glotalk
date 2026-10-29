package vn.onplay.vdubbing.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiLiveCredentialsTest {
    @Test fun previousProviderSettingsDoNotEnableGeminiOrReuseItsKey() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "live-migration-${UUID.randomUUID()}"
        val directory = File(target.cacheDir, id).apply { mkdirs() }
        val prefs = target.getSharedPreferences(id, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        try {
            File(directory, "openai-key").writeText("legacy-key")
            prefs.edit().putBoolean("openai_audio_enabled", true).commit()
            val settings = SettingsRepositoryImpl(context)
            settings.geminiKey = "screen-key"
            assertFalse(settings.geminiLiveAudioEnabled)
            assertEquals("", settings.geminiLiveKey)
            settings.geminiLiveKey = "live-key"
            settings.geminiLiveAudioEnabled = true
            assertEquals("screen-key", settings.geminiKey)
            assertEquals("live-key", SettingsRepositoryImpl(context).geminiLiveKey)
            assertTrue(SettingsRepositoryImpl(context).geminiLiveAudioEnabled)
        } finally {
            target.deleteSharedPreferences(id)
            directory.deleteRecursively()
        }
    }

    @Test fun encryptedKeySurvivesReopenAndCanBeRemoved() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "key-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = directory
        }
        try {
            val store = GeminiLiveCredentials(context)
            assertEquals("", store.read())
            store.write("AIza-test-personal-key")
            assertEquals("AIza-test-personal-key", GeminiLiveCredentials(context).read())
            val encrypted = File(directory, "gemini_live-key")
            assertFalse(encrypted.readBytes().toString(Charsets.UTF_8).contains("AIza-test-personal-key"))
            store.write("")
            assertEquals("", store.read())
            assertFalse(encrypted.exists())
            encrypted.writeText("invalid-ciphertext")
            assertEquals("", store.read())
        } finally { directory.deleteRecursively() }
    }
}
