package vn.onplay.vdubbing.domain.repository

interface ConnectivityRepository {
    fun isOnline(): Boolean
}
