package vn.onplay.vdubbing.framework.accessibility

/**
 * Whether this app's own activity is on screen.
 *
 * The accessibility service cannot answer that question from window events:
 * the overlay windows (bubble, panel, menu) belong to this same package and
 * fire TYPE_WINDOW_STATE_CHANGED exactly like an activity does. Trusting those
 * events made every translation started from the panel look like it was
 * requested against our own settings screen, which suppresses the overlay — the
 * translation ran and nothing was drawn.
 *
 * Counting resumed activities is the one signal the overlay windows cannot
 * forge. The service and the activity live in the same process, so a plain
 * counter is enough.
 */
object AppForeground {

    @Volatile
    private var resumedActivities = 0

    val isOurs: Boolean get() = resumedActivities > 0

    fun onActivityResumed() {
        resumedActivities++
    }

    fun onActivityPaused() {
        if (resumedActivities > 0) resumedActivities--
    }
}
