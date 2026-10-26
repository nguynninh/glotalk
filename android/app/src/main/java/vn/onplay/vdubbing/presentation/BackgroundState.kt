package vn.onplay.vdubbing.presentation

/** Published by the service so the UI knows whether background mode is armed. */
data class BackgroundState(
    val running: Boolean = false,
    val autoTranslate: Boolean = false,
    val lastSummary: String = "",
) {
    companion object {
        /** Process-wide; both the service and the ViewModel are in this process. */
        val shared = kotlinx.coroutines.flow.MutableStateFlow(BackgroundState())
    }
}
