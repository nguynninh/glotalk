package vn.onplay.vdubbing;

import vn.onplay.vdubbing.ITranslationCallback;

/**
 * Bound by other apps after requesting
 * <applicationId>.permission.CALL_TRANSLATE for the installed Glotalk variant.
 */
interface ITranslationService {
    /**
     * Capture the current screen, OCR it and translate it.
     *
     * @param sourceLang BCP-47-ish code ("en", "zh", "ja", "ko", "vi") or "auto".
     * @param targetLang same codes; "vi" is the usual target.
     * @param callback invoked exactly once, on a Binder thread.
     */
    void translateNow(String sourceLang, String targetLang, ITranslationCallback callback);

    /** Persist auto mode and re-run translation whenever the foreground window changes. */
    void setAutoTranslate(boolean enabled, String sourceLang, String targetLang);

    /** JSON of the most recent successful translation, or "" if none yet. */
    String getLastResult();

    /** True once the user has enabled the accessibility service in Settings. */
    boolean isAccessibilityReady();

    /** Remove the on-screen translation overlay. */
    void hideOverlay();
}
