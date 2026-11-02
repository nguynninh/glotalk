package vn.onplay.vdubbing.data.audio

import android.os.SystemClock
import android.util.Log
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import vn.onplay.vdubbing.BuildConfig

internal class AudioTrace(val id: String) : AbstractCoroutineContextElement(Key) {
    private val started = SystemClock.elapsedRealtime()

    fun log(event: String, details: String = "") {
        if (BuildConfig.DEBUG) Log.i("GlotalkAudio", "[$id] $event elapsed_ms=${SystemClock.elapsedRealtime() - started} $details")
    }

    companion object Key : CoroutineContext.Key<AudioTrace>
}

internal suspend fun traceAudio(event: String, details: String = "") {
    (currentCoroutineContext()[AudioTrace] ?: AudioTrace("setup")).log(event, details)
}
