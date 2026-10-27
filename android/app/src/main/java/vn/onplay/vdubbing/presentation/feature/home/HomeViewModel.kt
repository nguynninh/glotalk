package vn.onplay.vdubbing.presentation.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val savedState: SavedStateHandle) : ViewModel() {
    val selectedTab = savedState.getStateFlow("selectedTab", 0)
    val showHistory = savedState.getStateFlow("showHistory", false)

    fun selectTab(index: Int) { savedState["selectedTab"] = index }
    fun openHistory() { savedState["showHistory"] = true }
    fun closeHistory() { savedState["showHistory"] = false }
}
