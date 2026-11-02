package vn.onplay.vdubbing.data.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleSettingsTest {
    @Test fun bubbleDefaultsOnAndPersistsItsSelection() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bubble-settings-${UUID.randomUUID()}"
        val prefs = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        try {
            val settings = SettingsRepositoryImpl(context)
            assertTrue(settings.bubbleEnabled)
            settings.bubbleEnabled = false
            assertFalse(SettingsRepositoryImpl(context).bubbleEnabled)
            settings.bubbleEnabled = true
            assertTrue(SettingsRepositoryImpl(context).bubbleEnabled)
        } finally {
            target.deleteSharedPreferences(name)
        }
    }
}
