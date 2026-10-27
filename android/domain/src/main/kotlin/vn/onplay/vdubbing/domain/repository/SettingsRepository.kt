package vn.onplay.vdubbing.domain.repository

import vn.onplay.vdubbing.domain.model.AppLanguage
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.ThemeMode

interface SettingsRepository {
    var geminiKey: String
    var cloudFallbackEnabled: Boolean
    var targetLanguage: Language
    var autoTranslate: Boolean
    var themeMode: ThemeMode
    var appLanguage: AppLanguage

    /**
     * Language the screen is assumed to be in. [Language.AUTO] asks the
     * translator to detect it, which costs a call per capture and can be wrong
     * on short lines; naming it is both faster and steadier.
     */
    var sourceLanguage: Language

    /** Parallel or replace — see [DisplayMode]. */
    var displayMode: DisplayMode
}
