package vn.onplay.vdubbing.presentation

/** Environment facts the ViewModel needs but cannot ask Android for itself. */
interface AppEnvironment {
    val packageName: String
}
