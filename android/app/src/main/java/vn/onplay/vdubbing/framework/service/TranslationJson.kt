package vn.onplay.vdubbing.framework.service

import org.json.JSONArray
import org.json.JSONObject
import vn.onplay.vdubbing.domain.model.Translation

/**
 * The wire format returned by ITranslationService.translateNow and getLastResult.
 * Documented in docs/INTEGRATION.md; keep the two in sync.
 */
internal fun Translation.toJson(): String = JSONObject()
    .put("engine", engine.wireName)
    .put("sourceLang", source.code)
    .put("targetLang", target.code)
    .put("text", plainText)
    .put(
        "blocks",
        JSONArray().apply {
            blocks.forEach { block ->
                put(
                    JSONObject()
                        .put("original", block.original)
                        .put("translated", block.translated)
                        .put("lang", block.language.code)
                        .put("x", block.left)
                        .put("y", block.top)
                        .put("w", block.width)
                        .put("h", block.height),
                )
            }
        },
    )
    .toString()
