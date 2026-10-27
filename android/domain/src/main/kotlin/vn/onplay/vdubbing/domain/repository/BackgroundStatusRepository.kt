package vn.onplay.vdubbing.domain.repository

import kotlinx.coroutines.flow.StateFlow
import vn.onplay.vdubbing.domain.model.BackgroundState

interface BackgroundStatusRepository {
    val state: StateFlow<BackgroundState>
}
