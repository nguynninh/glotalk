package vn.onplay.vdubbing.data.audio

internal data class SpeechRequest(
    val text: String,
    val trace: AudioTrace,
    val onCompleted: suspend () -> Unit = {},
)
