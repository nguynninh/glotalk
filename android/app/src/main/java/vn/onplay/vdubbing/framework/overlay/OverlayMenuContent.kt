package vn.onplay.vdubbing.framework.overlay

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import vn.onplay.vdubbing.R

internal class OverlayMenuContent(private val context: Context, private val ui: OverlayUi) {
    fun build(
        state: PanelState,
        onToggleTranslation: () -> Unit,
        onLanguage: () -> Unit,
        onSettings: () -> Unit,
        onHideBubble: () -> Unit,
        onStopService: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Overlay.SURFACE, 20f)
            elevation = ui.px(8f).toFloat()
            setPadding(ui.px(6f), ui.px(8f), ui.px(6f), ui.px(8f))
        }

        // Identity and status: which app this bubble belongs to, and what it is
        // doing right now — the bubble itself has room for neither.
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.px(12f), ui.px(10f), ui.px(12f), ui.px(14f))
                addView(ui.bubbleAvatar(14f, ui.px(40f)))
                addView(
                    ui.column(
                        ui.text(ui.string(R.string.panel_title), 16f, Overlay.TEXT, bold = true),
                        ui.text(
                            ui.string(
                                when {
                                    state.audioActive -> state.audioStatusRes
                                    state.translating -> R.string.menu_status_translating
                                    state.hasTranslation -> R.string.menu_status_active
                                    else -> R.string.menu_status_idle
                                },
                            ),
                            12f,
                            Overlay.MUTED,
                            maxLines = 1,
                        ),
                    ).apply { setPadding(ui.px(12f), 0, 0, 0) },
                )
            },
        )
        card.addView(ui.divider(inset = 6f))

        // Pause is the contextual action: it is what the menu is for while a
        // translation is on screen, and it is inert otherwise.
        card.addView(
            ui.menuRow(
                iconRes = if (state.hasTranslation || state.audioActive) {
                    R.drawable.ic_overlay_pause
                } else {
                    R.drawable.ic_overlay_translate
                },
                label = ui.string(
                    if (state.audioActive) R.string.audio_stop
                    else if (state.hasTranslation) R.string.menu_pause else R.string.panel_start,
                ),
            ) {
                onToggleTranslation()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_language,
                label = ui.string(R.string.menu_language),
            ) {
                onLanguage()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_settings,
                label = ui.string(R.string.panel_advanced),
            ) {
                onSettings()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_hide,
                label = ui.string(R.string.menu_hide_bubble),
            ) {
                onHideBubble()
            },
        )
        card.addView(
            ui.menuRow(
                iconRes = R.drawable.ic_overlay_exit,
                label = ui.string(R.string.menu_exit),
            ) {
                onStopService()
            },
        )

        return card
    }
}
