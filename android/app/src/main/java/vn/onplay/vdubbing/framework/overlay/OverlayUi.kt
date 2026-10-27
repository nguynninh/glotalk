package vn.onplay.vdubbing.framework.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.framework.locale.AppLocaleController
import kotlin.math.roundToInt

/**
 * Colours and measurements for everything drawn over another app.
 *
 * The surfaces are plain white cards on a scrim, not themed: the window uses
 * the accessibility service's context, which has no Material theme attached,
 * and the card has to look the same whether the app underneath is light or
 * dark. For the same reason every string is read through [string], which
 * resolves against the language chosen *now* — an overlay window outlives a
 * language change, and Android does not recreate the service for it.
 */
internal class OverlayUi(private val context: Context) {

    val density: Float = context.resources.displayMetrics.density

    fun px(dp: Float): Int = (dp * density).roundToInt()

    fun sp(value: Float): Float = value * context.resources.displayMetrics.scaledDensity

    fun string(@StringRes id: Int, vararg args: Any): String =
        AppLocaleController.localized(context).getString(id, *args)

    /** Rounded rectangle: cards, chips, plates and the bubble all come from here. */
    fun rounded(
        color: Int,
        radiusDp: Float,
        strokeDp: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = px(radiusDp).toFloat()
        if (strokeDp > 0f) setStroke(px(strokeDp), strokeColor)
    }

    fun text(
        value: CharSequence,
        sizeSp: Float,
        color: Int = Overlay.TEXT,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
        includeFontPadding = false
        typeface = if (bold) Overlay.bold else Overlay.regular
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        this.gravity = gravity
    }

    fun icon(res: Int, sizeDp: Float, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    /** A row of the menu: icon, label, and the whole row is the touch target. */
    fun menuRow(iconRes: Int, label: CharSequence, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18f), 0, px(18f), 0)
            minimumHeight = px(56f)
            isClickable = true
            isFocusable = true
            background = selectableItem()
            addView(icon(iconRes, 22f, Overlay.TEXT))
            addView(
                text(label, 16f).apply {
                    setPadding(px(16f), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                },
            )
            setOnClickListener { onClick() }
        }

    private fun selectableItem(): android.graphics.drawable.Drawable? {
        // A ripple would need a themed context; the pressed state is enough here
        // and keeps this file free of AppCompat.
        val states = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(Overlay.HIGHLIGHT, Color.TRANSPARENT),
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(12f).toFloat()
            color = states
        }
    }

    /** The blue "TA" disc, shared by the bubble and the menu header. */
    fun bubbleAvatar(textSizeSp: Float, diameter: Int): FrameLayout =
        FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Overlay.BLUE)
            }
            minimumWidth = diameter
            minimumHeight = diameter
            addView(
                TextView(context).apply {
                    text = BUBBLE_TEXT
                    textSize = textSizeSp
                    typeface = Overlay.bold
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(diameter, diameter),
            )
        }

    companion object {
        const val BUBBLE_TEXT = "TA"

        /**
         * Short badge for a language, e.g. "EN" or "VI". Flags would be nicer
         * but several OEM fonts ship no flag glyphs, so the badge is text that
         * always renders.
         */
        fun badge(language: Language): String =
            if (language == Language.AUTO) "A" else language.code.uppercase()
    }
}

/** The palette of the overlay, in one place so the windows agree. */
internal object Overlay {
    const val BLUE = 0xFF1A73E8.toInt()
    const val BLUE_SOFT = 0xFFE8F0FE.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val SCRIM = 0x73000000
    const val TEXT = 0xFF202124.toInt()
    const val MUTED = 0xFF5F6368.toInt()
    const val DIVIDER = 0xFFE6E6E8.toInt()
    const val BORDER = 0xFFD6D6DA.toInt()
    const val HIGHLIGHT = 0x14000000
    const val CHIP = 0xFFF1F3F4.toInt()
    const val DANGER = 0xFFD32F2F.toInt()

    val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
}
