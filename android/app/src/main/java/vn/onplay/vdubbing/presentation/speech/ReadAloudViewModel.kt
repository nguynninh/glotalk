package vn.onplay.vdubbing.presentation.speech

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import vn.onplay.vdubbing.domain.repository.VietnameseSpeechRepository

@HiltViewModel
internal class ReadAloudViewModel @Inject constructor(
    internal val vietnamese: VietnameseSpeechRepository,
) : ViewModel()
