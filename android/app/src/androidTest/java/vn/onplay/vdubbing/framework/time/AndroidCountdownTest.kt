package vn.onplay.vdubbing.framework.time

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCountdownTest {
    @Test fun countdownExpiresAndCancellationDoesNotRunTheAction() = runBlocking {
        withTimeout(2000) {
            val began = SystemClock.elapsedRealtime()
            AndroidCountdown().await(40)
            assertTrue(SystemClock.elapsedRealtime() - began >= 40)
            var completed = false
            val timer = launch(Dispatchers.Main.immediate) {
                AndroidCountdown().await(5000)
                completed = true
            }
            timer.cancelAndJoin()
            assertFalse(completed)
        }
    }
}
