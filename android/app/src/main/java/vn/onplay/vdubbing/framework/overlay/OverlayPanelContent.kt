package vn.onplay.vdubbing.framework.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.DisplayMode
import vn.onplay.vdubbing.domain.model.Language

internal class OverlayPanelContent(
    private val context: Context,
    private val ui: OverlayUi,
    private val hostProvider: () -> OverlayHost?,
    private val closePanel: () -> Unit,
    private val refreshPanel: () -> Unit,
    private val onTranslateTap: () -> Unit,
) {
    private val host: OverlayHost? get() = hostProvider()
    private enum class Picker { SOURCE, TARGET }
    private var picker: Picker? = null

    fun reset() { picker = null }

    fun build(state: PanelState): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Overlay.SURFACE, 24f)
            elevation = ui.px(8f).toFloat()
            setPadding(ui.px(20f), ui.px(18f), ui.px(20f), ui.px(18f))
        }

        // Header: title, then the close that the scrim would also do.
        card.addView(ui.header(ui.string(R.string.panel_title)) { closePanel() })
        card.addView(ui.gap(16f))

        // Keep the dropdown inside this overlay window so taps stay in the panel.
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    languageChip(state.source) {
                        toggleLanguagePicker(Picker.SOURCE)
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(
                    ui.icon(R.drawable.ic_overlay_swap, 20f, Overlay.MUTED).apply {
                        setPadding(ui.px(8f), 0, ui.px(8f), 0)
                        contentDescription = ui.string(R.string.panel_swap_languages)
                        isClickable = true
                        setOnClickListener {
                            // Swapping a detected source makes no sense: there is
                            // nothing to name on the other side.
                            if (state.source != Language.AUTO) {
                                host?.onSourceLanguage(state.target)
                                host?.onTargetLanguage(state.source)
                            }
                            refreshPanel()
                        }
                    },
                )
                addView(
                    languageChip(state.target) {
                        toggleLanguagePicker(Picker.TARGET)
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
            },
        )

        // Preserve an open dropdown when another panel setting changes.
        when (picker) {
            Picker.SOURCE -> card.addView(languageDropdown(state.source, Language.SOURCES, isSource = true))
            Picker.TARGET -> card.addView(languageDropdown(state.target, Language.TARGETS, isSource = false))
            null -> Unit
        }

        card.addView(ui.gap(16f))
        card.addView(ui.divider())
        card.addView(ui.gap(4f))

        // Realtime translation.
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.px(58f)
                addView(
                    ui.column(
                        ui.text(ui.string(R.string.panel_realtime_title), 16f, Overlay.TEXT),
                        ui.text(
                            ui.string(R.string.panel_realtime_subtitle),
                            12f,
                            Overlay.MUTED,
                            maxLines = 1,
                        ),
                    ),
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                addView(
                    Switch(context).apply {
                        isChecked = state.autoTranslate
                        // Redrawn after the change so the subtitle reflects it;
                        // rebuilding from the event would re-enter this switch.
                        setOnCheckedChangeListener { _, checked ->
                            host?.onAutoTranslate(checked)
                            post { refreshPanel() }
                        }
                    },
                )
            },
        )

        card.addView(ui.divider())
        card.addView(ui.gap(14f))

        // Display mode.
        card.addView(ui.text(ui.string(R.string.panel_mode_title), 15f, Overlay.TEXT, bold = true))
        card.addView(ui.gap(10f))
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    modeCard(
                        iconRes = R.drawable.ic_overlay_parallel,
                        title = ui.string(R.string.panel_mode_parallel),
                        subtitle = ui.string(R.string.panel_mode_parallel_desc),
                        selected = state.displayMode == DisplayMode.PARALLEL,
                    ) {
                        host?.onDisplayMode(DisplayMode.PARALLEL)
                        refreshPanel()
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        rightMargin = ui.px(6f)
                    },
                )
                addView(
                    modeCard(
                        iconRes = R.drawable.ic_overlay_replace,
                        title = ui.string(R.string.panel_mode_replace),
                        subtitle = ui.string(R.string.panel_mode_replace_desc),
                        selected = state.displayMode == DisplayMode.REPLACE,
                    ) {
                        host?.onDisplayMode(DisplayMode.REPLACE)
                        refreshPanel()
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        leftMargin = ui.px(6f)
                    },
                )
            },
        )

        card.addView(ui.gap(6f))
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_settings,
                label = ui.string(R.string.panel_advanced),
            ) {
                closePanel()
                host?.onOpenAppSettings()
            }.apply { setPadding(0, 0, 0, 0) },
        )
        card.addView(ui.gap(10f))

        // Primary action: start, or a progress label while one is running.
        card.addView(
            ui.text(
                if (state.translating) {
                    ui.string(R.string.settings_translating)
                } else {
                    ui.string(R.string.panel_start)
                },
                16f,
                Color.WHITE,
                bold = true,
                maxLines = 1,
                gravity = Gravity.CENTER,
            ).apply {
                gravity = Gravity.CENTER
                background = ui.rounded(Overlay.BLUE, 26f)
                minHeight = ui.px(52f)
                isClickable = true
                isEnabled = !state.translating
                alpha = if (state.translating) 0.7f else 1f
                setOnClickListener {
                    closePanel()
                    host?.onStartTranslation() ?: onTranslateTap()
                }
            },
        )
        return card
    }

    private fun toggleLanguagePicker(requested: Picker) {
        picker = if (picker == requested) null else requested
        refreshPanel()
    }

    private fun languageDropdown(
        selected: Language,
        choices: List<Language>,
        isSource: Boolean,
    ): View = ScrollView(context).apply {
        background = ui.rounded(Overlay.CHIP, 14f)
        setPadding(ui.px(6f), ui.px(6f), ui.px(6f), ui.px(6f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ui.px(160f),
        ).apply { topMargin = ui.px(8f) }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                choices.forEach { language ->
                    addView(languageOption(language, language == selected) {
                        if (isSource) host?.onSourceLanguage(language)
                        else host?.onTargetLanguage(language)
                        picker = null
                        refreshPanel()
                    })
                }
            },
        )
    }

    private fun languageOption(language: Language, selected: Boolean, onClick: () -> Unit): View =
        ui.text(
            language.pickerLabel(),
            13f,
            if (selected) Color.WHITE else Overlay.TEXT,
            bold = selected,
            maxLines = 1,
        ).apply {
            background = ui.rounded(if (selected) Overlay.BLUE else Color.TRANSPARENT, 10f)
            setPadding(ui.px(14f), ui.px(9f), ui.px(14f), ui.px(9f))
            minimumHeight = ui.px(48f)
            gravity = Gravity.CENTER_VERTICAL
            isSelected = selected
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun languageChip(language: Language, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Overlay.CHIP, 14f)
            setPadding(ui.px(10f), ui.px(10f), ui.px(10f), ui.px(10f))
            isClickable = true
            addView(
                ui.text(OverlayUi.badge(language), 10f, Overlay.BLUE, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = ui.rounded(Overlay.BLUE_SOFT, 6f)
                    minWidth = ui.px(26f)
                    setPadding(ui.px(5f), ui.px(3f), ui.px(5f), ui.px(3f))
                },
            )
            addView(
                ui.text(language.pickerLabel(), 14f, Overlay.TEXT, bold = true, maxLines = 1)
                    .apply {
                        setPadding(ui.px(8f), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    },
            )
            addView(ui.icon(R.drawable.ic_overlay_chevron, 16f, Overlay.MUTED).apply {
                rotation = 90f
            })
            setOnClickListener { onClick() }
        }

    private fun modeCard(
        iconRes: Int,
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ui.rounded(
            if (selected) Overlay.BLUE_SOFT else Overlay.SURFACE,
            14f,
            if (selected) 2f else 1f,
            if (selected) Overlay.BLUE else Overlay.BORDER,
        )
        setPadding(ui.px(10f), ui.px(14f), ui.px(10f), ui.px(14f))
        isClickable = true
        addView(ui.icon(iconRes, 22f, if (selected) Overlay.BLUE else Overlay.MUTED))
        addView(
            ui.text(title, 14f, Overlay.TEXT, bold = true, gravity = Gravity.CENTER)
                .apply { setPadding(0, ui.px(8f), 0, 0) },
        )
        addView(
            ui.text(subtitle, 11f, Overlay.MUTED, gravity = Gravity.CENTER, maxLines = 2)
                .apply { setPadding(0, ui.px(3f), 0, 0) },
        )
        setOnClickListener { onClick() }
    }

    /**
     * Language names in the chips are endonyms — a language is always written
     * in itself — except AUTO, which describes the app's behaviour rather than
     * a language and therefore follows the interface language.
     */
    private fun Language.pickerLabel(): String =
        if (this == Language.AUTO) ui.string(R.string.language_auto) else label

}
