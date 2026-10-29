package vn.onplay.vdubbing.framework.time

import android.os.CountDownTimer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.domain.time.Countdown

internal class AndroidCountdown : Countdown {
    override suspend fun await(milliseconds: Long) = withContext(Dispatchers.Main.immediate) {
        if (milliseconds <= 0) return@withContext
        suspendCancellableCoroutine { continuation ->
            val timer = object : CountDownTimer(milliseconds, milliseconds) {
                override fun onTick(millisUntilFinished: Long) = Unit
                override fun onFinish() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            timer.start()
            continuation.invokeOnCancellation { timer.cancel() }
        }
    }
}
