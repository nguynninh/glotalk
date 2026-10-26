package vn.onplay.vdubbing;

/**
 * Result delivery for a translateNow() request.
 *
 * IMPORTANT: both callbacks arrive on a Binder thread, never the main thread.
 * Marshal to the main looper before touching UI.
 */
oneway interface ITranslationCallback {
    /** resultJson: see docs/INTEGRATION.md for the schema. */
    void onResult(String resultJson);

    /** code: 1 no accessibility, 2 capture failed, 3 no text, 4 translate failed,
     *         5 bad API key, 6 cloud quota, 7 offline, 8 busy. */
    void onError(int code, String message);
}
