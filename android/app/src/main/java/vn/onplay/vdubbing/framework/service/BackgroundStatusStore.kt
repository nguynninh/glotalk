package vn.onplay.vdubbing.framework.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.onplay.vdubbing.domain.model.BackgroundState
import vn.onplay.vdubbing.domain.repository.BackgroundStatusRepository

class BackgroundStatusStore : BackgroundStatusRepository {
    private val mutableState = MutableStateFlow(BackgroundState())
    override val state = mutableState.asStateFlow()

    internal fun publish(state: BackgroundState) {
        mutableState.value = state
    }
}
