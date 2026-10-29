package vn.onplay.vdubbing.domain.time

fun interface Countdown {
    suspend fun await(milliseconds: Long)
}
