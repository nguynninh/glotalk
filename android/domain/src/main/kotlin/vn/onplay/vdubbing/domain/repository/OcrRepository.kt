package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.Script
import vn.onplay.vdubbing.domain.model.TextBlock

interface OcrRepository {
    suspend fun recognize(screen: CapturedScreen, script: Script): List<TextBlock>
}
