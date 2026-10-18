// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.common

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_HOLO
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_MATERIAL
import helium314.keyboard.latin.common.ColorType.*
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.adjustLuminosityAndKeepAlpha
import helium314.keyboard.latin.utils.brighten
import helium314.keyboard.latin.utils.brightenOrDarken
import helium314.keyboard.latin.utils.darken
import helium314.keyboard.latin.utils.isBrightColor
import helium314.keyboard.latin.utils.isDarkColor
import java.util.EnumMap
import androidx.core.graphics.drawable.toDrawable
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

interface Colors {
    // these theme parameters should no be in here, but are still used
    /** used in KeyboardView for label placement */
    val themeStyle: String
    /** used in parser to decide background of ZWNJ key */
    val hasKeyBorders: Boolean
    /** whether key hint labels/icons (the small characters in the key corners) are drawn.
     *  Designs that place nothing in the corners set this to false. */
    val showKeyHints: Boolean get() = true
    /** REA fork: the colour of the rules between the suggestion areas, or null to keep whatever
     *  colour the divider drawable of the theme style was painted in.
     *
     *  A theme that states one also selects the white divider drawable (see SuggestionStripView),
     *  because a tint composites with the source alpha: over the white drawable this value is
     *  exactly the colour that ends up on screen, which is what makes it tunable in one place. */
    val suggestionsDivider: Int? get() = null
    /** REA fork: the soft shading a design may want *on top of* its surfaces, or null for none.
     *
     *  The design expresses its soft edges with CSS box-shadows. A hardware-accelerated Android
     *  canvas can not draw a shadow for a shape, and the tint path can not either, because it
     *  multiplies (or, for glass keys, replaces) the surface colour and therefore can never produce
     *  a colour lighter than the surface. So the shading is a layer of its own, drawn untinted over
     *  the surface. */
    val surfaceShading: SurfaceShading? get() = null
    /** REA fork: whether the panel wants the navigation bar to be transparent, so that the panel and
     *  its background image run to the bottom of the screen and show under the gesture pill instead
     *  of stopping at the bar. False colours the bar with [ColorType.NAVIGATION_BAR], as upstream. */
    val panelUnderNavigationBar: Boolean get() = false
    /** REA fork: the label of the key that opens the symbols layout, or null to keep the one the
     *  framework or the locale's own key texts provide (English reads "?123"). */
    val symbolKeyLabel: String? get() = null
    /** REA fork: false hides the "more suggestions" hint, the "…" the strip draws under a suggestion
     *  when more words are available. */
    val showMoreSuggestionsHint: Boolean get() = true
    /** REA fork: how tall the suggestion dividers are, as a fraction of the strip height; null keeps
     *  the height the divider drawable brings itself. */
    val suggestionsDividerHeightRatio: Float? get() = null
    /** REA fork: true draws the key preview as a bubble that stops at the top edge of the key and
     *  lets the long-press panel sit on the key itself, so nothing connects the two. False keeps
     *  upstream's preview: a tail that reaches down to the key, with the panel aligned to it. */
    val previewBubbleAboveKey: Boolean get() = false
    /** REA fork: the colour the strip's toolbar toggle glyph is drawn in when a theme wants that
     *  toggle as a bare glyph, with no disc behind it. Null keeps the style's disc and its glyph. */
    val toolbarToggleGlyphColor: Int? get() = null
    /** REA fork: how wide a theme's key preview bubble is, as a multiple of the key it previews.
     *  Null keeps the width the preview style's own drawable gives the preview view. */
    val keyPreviewWidthRatio: Float? get() = null
    /** REA fork: how tall a theme's key preview bubble stands above the key, as a multiple of the
     *  key's height. Null keeps the height the preview style's own drawable gives it. */
    val keyPreviewHeightRatio: Float? get() = null
    val accentShiftedIcon: Boolean get() =
        Settings.getCurrentContext().prefs().getBoolean(Settings.PREF_ACCENT_SHIFTED_ICON, Defaults.PREF_ACCENT_SHIFTED_ICON)

    /** use to check whether colors have changed, for colors (in)directly derived from context,
     *  e.g. night mode or potentially changing system colors */
    fun haveColorsChanged(context: Context): Boolean = false

    /** get the colorInt */
    @ColorInt fun get(color: ColorType): Int

    /** apply a color to the [drawable], may be through color filter or tint (with or without state list) */
    fun setColor(drawable: Drawable, color: ColorType)

    /** set a foreground color to the [view] */
    fun setColor(view: ImageView, color: ColorType)

    /** REA fork: set an explicit foreground colour on the [view], for a theme that paints an icon
     *  from its own palette instead of through a [ColorType]. The filter is the one [setColor] uses,
     *  so a themed icon and a palette-painted one are tinted the same way. */
    fun setColor(view: ImageView, color: Int) {
        view.colorFilter = colorFilter(color)
    }

    /** set a background to the [view], may replace or adjust existing background */
    fun setBackground(view: View, color: ColorType)

    /** REA fork: draw the key preview as the theme's own bubble, cut off [tail] pixels above its
     *  bottom edge so that it stops at the top edge of the key it previews. The default is the
     *  style's own preview, drawn whole, which is what every other theme gets. */
    fun setKeyPreviewBackground(view: View, tail: Int) {
        setBackground(view, ColorType.KEY_PREVIEW_BACKGROUND)
    }

    /** returns a colored drawable selected from [attr], which must contain using R.styleable.KeyboardView_* */
    fun selectAndColorDrawable(attr: TypedArray, color: ColorType): Drawable {
        val drawable = when (color) {
            KEY_BACKGROUND, MORE_SUGGESTIONS_WORD_BACKGROUND, ACTION_KEY_POPUP_KEYS_BACKGROUND, POPUP_KEYS_BACKGROUND ->
                attr.getDrawable(R.styleable.KeyboardView_keyBackground)
            FUNCTIONAL_KEY_BACKGROUND -> attr.getDrawable(R.styleable.KeyboardView_functionalKeyBackground)
            SPACE_BAR_BACKGROUND -> {
                if (hasKeyBorders) attr.getDrawable(R.styleable.KeyboardView_spacebarBackground)
                else attr.getDrawable(R.styleable.KeyboardView_spacebarNoBorderBackground)
            }
            ACTION_KEY_BACKGROUND -> {
                if (themeStyle == STYLE_HOLO && hasKeyBorders) // no borders has a very small pressed drawable otherwise
                    attr.getDrawable(R.styleable.KeyboardView_functionalKeyBackground)
                else
                    attr.getDrawable(R.styleable.KeyboardView_keyBackground)
            }
            else -> null // keyBackground
        }?.mutate() ?: attr.getDrawable(R.styleable.KeyboardView_keyBackground)?.mutate()!! // keyBackground always exists

        setColor(drawable, color)
        return drawable
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class DynamicColors(context: Context, override val themeStyle: String, override val hasKeyBorders: Boolean, private var keyboardBackground: Drawable? = null) : Colors {

    private val isNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val accent = getAccent(context)
    private val gesture = getGesture(context)
    private val background = getBackground(context)
    private val keyBackground = getKeyBackground(context)
    private val functionalKey = getFunctionalKey(context)
    private val spaceBar = getKeyBackground(context)
    private val keyText = getKeyText(context)
    private val keyHintText = getKeyHintText(context)
    private val spaceBarText = getSpaceBarText(context)

    private fun getAccent(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_accent1_100)
        else ContextCompat.getColor(context, android.R.color.system_accent1_200)
    private fun getGesture(context: Context) = if (isNight) accent
        else ContextCompat.getColor(context, android.R.color.system_accent1_600)
    private fun getBackground(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_900)
        else ContextCompat.getColor(context, android.R.color.system_neutral1_50)
    private fun getKeyBackground(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_800)
        else  ContextCompat.getColor(context, android.R.color.system_neutral1_0)
    private fun getFunctionalKey(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_accent2_300)
        else ContextCompat.getColor(context, android.R.color.system_accent2_100)
    private fun getKeyText(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_50)
        else ContextCompat.getColor(context, android.R.color.system_accent3_900)
    private fun getKeyHintText(context: Context) = if (isNight) keyText
        else ContextCompat.getColor(context, android.R.color.system_accent3_700)
    private fun getSpaceBarText(context: Context) = if (isNight) ColorUtils.setAlphaComponent(ContextCompat.getColor(context, android.R.color.system_neutral1_50), 127)
        else ColorUtils.setAlphaComponent(ContextCompat.getColor(context, android.R.color.system_accent3_700), 127)

    override fun haveColorsChanged(context: Context) =
        accent != getAccent(context)
                || gesture != getGesture(context)
                || background != getBackground(context)
                || keyBackground != getKeyBackground(context)
                || functionalKey != getFunctionalKey(context)
                || keyText != getKeyText(context)
                || keyHintText != getKeyHintText(context)
                || spaceBarText != getSpaceBarText(context)

    private val navBar: Int
    /** brightened or darkened variant of [background], to be used if exact background color would be
     *  bad contrast, e.g. popup keys popup or no border space bar */
    private val adjustedBackground: Int
    /** further brightened or darkened variant of [adjustedBackground] */
    private val doubleAdjustedBackground: Int
    /** brightened or darkened variant of [keyText] */
    private val adjustedKeyText: Int

    private val backgroundFilter: ColorFilter
    private val adjustedBackgroundFilter: ColorFilter
    private val keyTextFilter: ColorFilter
    private val accentColorFilter: ColorFilter
    /** color filter for the white action key icons in material theme, switches to gray if necessary for contrast */
    private val actionKeyIconColorFilter: ColorFilter?

    private val backgroundStateList: ColorStateList
    private val keyStateList: ColorStateList
    private val functionalKeyStateList: ColorStateList
    private val actionKeyStateList: ColorStateList
    private val spaceBarStateList: ColorStateList
    private val adjustedBackgroundStateList: ColorStateList
    private val stripBackgroundList: ColorStateList
    private val toolbarKeyStateList = activatedStateList(
        keyText,
        if (isBrightColor(keyText)) darken(darken(keyText))
        else brighten(brighten(keyText))
    )

    /** darkened variant of [accent] because the accent color is always light for dynamic colors */
    private val adjustedAccent: Int = darken(accent)
    /** further darkened variant of [adjustedAccent] */
    private val doubleAdjustedAccent: Int = darken(adjustedAccent)

    /** darkened variant of [functionalKey] used in day mode */
    private val adjustedFunctionalKey: Int = darken(functionalKey)
    /** further darkened variant of [adjustedFunctionalKey] */
    private val doubleAdjustedFunctionalKey: Int = darken(adjustedFunctionalKey)

    /** brightened variant of [keyBackground] used in night mode */
    private val adjustedKeyBackground: Int = brighten(keyBackground)
    /** further brightened variant of [adjustedKeyBackground] */
    private val doubleAdjustedKeyBackground: Int = brighten(adjustedKeyBackground)
    private var backgroundSetupDone = false

    init {
        accentColorFilter = colorFilter(doubleAdjustedAccent)

        if (themeStyle == STYLE_HOLO && keyboardBackground == null) {
            val darkerBackground = adjustLuminosityAndKeepAlpha(background, -0.2f)
            navBar = darkerBackground
            keyboardBackground = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(background, darkerBackground))
            backgroundSetupDone = true
        } else {
            navBar = background
        }

        // todo (idea): make better use of the states?
        //  could also use / create StateListDrawables in colors (though that's a style than a color...)
        //  this would better allow choosing e.g. cornered/rounded drawables for poup keys or moreSuggestions
        backgroundFilter = colorFilter(background)
        adjustedKeyText = brightenOrDarken(keyText, true)

        if (isDarkColor(background)) {
            adjustedBackground = brighten(background)
            doubleAdjustedBackground = brighten(adjustedBackground)
        } else {
            adjustedBackground = darken(background)
            doubleAdjustedBackground = darken(adjustedBackground)
        }
        adjustedBackgroundStateList =
            if (themeStyle == STYLE_HOLO) {
                pressedStateList(accent, adjustedBackground)
            } else if (isNight) {
                if (hasKeyBorders) pressedStateList(doubleAdjustedAccent, keyBackground)
                else pressedStateList(adjustedAccent, adjustedKeyBackground)
            } else {
                pressedStateList(accent, Color.WHITE)
            }

        val stripBackground = if (keyboardBackground == null && !hasKeyBorders) {
            if (isDarkColor(background)) 0x16ffffff else 0x11000000
        } else {
            Color.TRANSPARENT
        }
        val pressedStripElementBackground = if (keyboardBackground == null) adjustedBackground
        else if (isDarkColor(background)) 0x22ffffff else 0x11000000
        stripBackgroundList = pressedStateList(pressedStripElementBackground, stripBackground)

        adjustedBackgroundFilter =
            if (themeStyle == STYLE_HOLO) colorFilter(adjustedBackground)
            else colorFilter(keyBackground)

        if (hasKeyBorders) {
            backgroundStateList =
                if (!isNight) pressedStateList(adjustedFunctionalKey, background)
                else pressedStateList(adjustedKeyBackground, background)

            keyStateList =
                if (!isNight) pressedStateList(adjustedBackground, keyBackground)
                else pressedStateList(adjustedKeyBackground, keyBackground)

            functionalKeyStateList =
                if (!isNight) pressedStateList(doubleAdjustedFunctionalKey, functionalKey)
                else pressedStateList(functionalKey, doubleAdjustedKeyBackground)

            actionKeyStateList =
                if (!isNight) pressedStateList(gesture, accent)
                else pressedStateList(doubleAdjustedAccent, accent)

            spaceBarStateList =
                if (themeStyle == STYLE_HOLO) pressedStateList(spaceBar, spaceBar)
                else keyStateList

        } else {
            // need to set color to background if key borders are disabled, or there will be ugly keys
            backgroundStateList =
                if (!isNight) pressedStateList(adjustedFunctionalKey, background)
                else pressedStateList(adjustedKeyBackground, background)

            keyStateList =
                if (!isNight) pressedStateList(adjustedFunctionalKey, Color.TRANSPARENT)
                else pressedStateList(functionalKey, Color.TRANSPARENT)

            functionalKeyStateList =
                if (themeStyle == STYLE_HOLO) pressedStateList(functionalKey, Color.TRANSPARENT)
                else keyStateList

            actionKeyStateList =
                if (themeStyle == STYLE_HOLO) pressedStateList(accent, Color.TRANSPARENT)
                else if (!isNight) pressedStateList(gesture, accent)
                else pressedStateList(doubleAdjustedAccent, accent)

            spaceBarStateList =
                if (!isNight) pressedStateList(gesture, adjustedFunctionalKey)
                else pressedStateList(adjustedKeyBackground, spaceBar)
        }
        keyTextFilter = colorFilter(keyText)

        actionKeyIconColorFilter = when {
            themeStyle == STYLE_HOLO -> keyTextFilter
            // the white icon may not have enough contrast, and can't be adjusted by the user
            isBrightColor(accent) -> colorFilter(Color.DKGRAY)
            else -> null
        }
    }

    override fun get(color: ColorType): Int = when (color) {
        TOOL_BAR_KEY_ENABLED_BACKGROUND, EMOJI_CATEGORY_SELECTED, ACTION_KEY_BACKGROUND, CLIPBOARD_PIN -> accent
        SHIFT_KEY_ICON -> if (accentShiftedIcon) accent else keyText
        AUTOFILL_BACKGROUND_CHIP, GESTURE_PREVIEW, POPUP_KEYS_BACKGROUND, MORE_SUGGESTIONS_BACKGROUND, KEY_PREVIEW_BACKGROUND -> adjustedBackground
        TOOL_BAR_EXPAND_KEY_BACKGROUND -> if (!isNight) accent else doubleAdjustedBackground
        GESTURE_TRAIL -> gesture
        KEY_TEXT, SUGGESTION_AUTO_CORRECT, REMOVE_SUGGESTION_ICON, EMOJI_KEY_TEXT, KEY_PREVIEW_TEXT, POPUP_KEY_TEXT,
            KEY_ICON, POPUP_KEY_ICON, ONE_HANDED_MODE_BUTTON, EMOJI_CATEGORY, TOOL_BAR_KEY, FUNCTIONAL_KEY_TEXT,
            EMOJI_SEARCH_TEXT, CLIPBOARD_SUGGESTION_ICON -> keyText
        KEY_HINT_TEXT -> keyHintText
        SPACE_BAR_TEXT -> spaceBarText
        FUNCTIONAL_KEY_BACKGROUND, EMOJI_SEARCH_BACKGROUND -> if (!isNight) functionalKey else doubleAdjustedKeyBackground
        SPACE_BAR_BACKGROUND -> spaceBar
        MORE_SUGGESTIONS_WORD_BACKGROUND, MAIN_BACKGROUND -> background
        KEY_BACKGROUND -> keyBackground
        ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackground else accent
        STRIP_BACKGROUND -> if (!hasKeyBorders && themeStyle == STYLE_MATERIAL) adjustedBackground else background
        CLIPBOARD_SUGGESTION_BACKGROUND -> keyBackground
        NAVIGATION_BAR -> navBar
        MORE_SUGGESTIONS_HINT, SUGGESTED_WORD, SUGGESTION_TYPED_WORD, SUGGESTION_VALID_WORD -> adjustedKeyText
        ACTION_KEY_ICON, TOOL_BAR_EXPAND_KEY -> Color.WHITE
    }

    override fun setColor(drawable: Drawable, color: ColorType) {
        val colorStateList = when (color) {
            MORE_SUGGESTIONS_WORD_BACKGROUND -> backgroundStateList
            KEY_BACKGROUND -> keyStateList
            FUNCTIONAL_KEY_BACKGROUND -> functionalKeyStateList
            ACTION_KEY_BACKGROUND -> actionKeyStateList
            SPACE_BAR_BACKGROUND -> spaceBarStateList
            POPUP_KEYS_BACKGROUND -> adjustedBackgroundStateList
            STRIP_BACKGROUND -> stripBackgroundList
            ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackgroundStateList
                else actionKeyStateList
            TOOL_BAR_KEY -> toolbarKeyStateList
            else -> null // use color filter
        }
        if (colorStateList == null) {
            drawable.colorFilter = getColorFilter(color)
            return
        }
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.MULTIPLY)
        DrawableCompat.setTintList(drawable, colorStateList)
    }

    override fun setColor(view: ImageView, color: ColorType) {
        if (color == TOOL_BAR_KEY) {
            setColor(view.drawable, color)
            return
        }
        view.colorFilter = getColorFilter(color)
    }

    private fun getColorFilter(color: ColorType): ColorFilter? = when (color) {
        EMOJI_CATEGORY_SELECTED, CLIPBOARD_PIN -> accentColorFilter
        SHIFT_KEY_ICON -> if (accentShiftedIcon) accentColorFilter else keyTextFilter
        REMOVE_SUGGESTION_ICON, EMOJI_CATEGORY, KEY_TEXT,
            KEY_ICON, ONE_HANDED_MODE_BUTTON, TOOL_BAR_KEY, TOOL_BAR_EXPAND_KEY -> keyTextFilter
        KEY_PREVIEW_BACKGROUND -> adjustedBackgroundFilter
        ACTION_KEY_ICON -> actionKeyIconColorFilter
        else -> colorFilter(get(color))
    }

    override fun setBackground(view: View, color: ColorType) {
        if (view.background == null)
            view.setBackgroundColor(Color.WHITE) // set white to make the color filters work
        when (color) {
            KEY_PREVIEW_BACKGROUND -> view.background.colorFilter = adjustedBackgroundFilter
            FUNCTIONAL_KEY_BACKGROUND, KEY_BACKGROUND, MORE_SUGGESTIONS_WORD_BACKGROUND, SPACE_BAR_BACKGROUND, STRIP_BACKGROUND, CLIPBOARD_SUGGESTION_BACKGROUND -> setColor(view.background, color)
            ONE_HANDED_MODE_BUTTON -> setColor(view.background, if (keyboardBackground == null) MAIN_BACKGROUND else STRIP_BACKGROUND)
            MORE_SUGGESTIONS_BACKGROUND -> view.background.colorFilter = backgroundFilter
            POPUP_KEYS_BACKGROUND ->
                if (themeStyle != STYLE_HOLO)
                    setColor(view.background, POPUP_KEYS_BACKGROUND)
                else view.background.colorFilter = adjustedBackgroundFilter
            MAIN_BACKGROUND -> {
                // rasterizing needs a laid-out panel to know how big the bitmap has to be, so it is
                // retried on the next call while the panel has no size yet (see panelBackgroundOrNull
                // for this and the undecodable-image case). The source image is only replaced once it
                // really became a panel-sized bitmap.
                val image = keyboardBackground
                val panel = when {
                    image == null -> null
                    backgroundSetupDone -> image
                    else -> panelBackgroundOrNull(image, view)?.also {
                        keyboardBackground = it
                        backgroundSetupDone = true
                    }
                }
                if (panel != null) {
                    view.background = panel
                } else {
                    // no background image (yet): the panel is the theme's flat panel color
                    view.background.colorFilter = backgroundFilter
                }
            }
            else -> view.background.colorFilter = backgroundFilter
        }
    }
}

/**
 * Per-key alphas for a theme that fakes glass keys, i.e. that wants the key material to be genuinely
 * translucent so whatever sits behind it shows through. An alpha of 255 (opaque) leaves the keys
 * rendering exactly as they do for every other theme.
 *
 * These are alphas, not colors: the key color still comes from the theme, only its opacity is
 * overridden. See [DefaultColors.setColor] for how the alpha is applied.
 *
 * These alphas reach the keys only. The panel and the suggestion strip are the IME window's own
 * surface and carry their own colour instead (see [DefaultColors.panelOverImage]).
 */
data class GlassAlphas(
    /** character keys */
    val key: Int,
    /** character keys while pressed */
    val keyPressed: Int,
    /** functional keys (shift / delete / globe) */
    val functionalKey: Int,
    /** functional keys while pressed */
    val functionalKeyPressed: Int,
)

/**
 * REA fork: the soft shading a design may want *on top of* its surfaces — the Android stand-in for
 * the box-shadows the design is written with.
 *
 * It has to be a layer of its own: a key surface is painted by tinting a drawable with the theme's
 * key colour, and a tint can only multiply (or, for glass keys, only replace) that colour, so it can
 * never produce anything lighter than the surface. A separate drawable that is not tinted can, and
 * is therefore also where the "soft edge" with no hard border comes from.
 */
data class SurfaceShading(
    /** how dark a key's lower edge falls off; 0 leaves the key fill flat, which is what the design wants */
    val keyEdgeShade: Int,
    /** the colour the panel's lower edge falls off into */
    val panelEdgeShade: Int,
    /** the hairline highlight along the top inside edge of a key */
    val keyTopHighlight: Int,
    /** corner radius of a keycap, in dp: keys, popup keys and emoji keys all use it */
    val keyCornerRadiusDp: Float,
    /** corner radius of the top corners of the panel, in dp */
    val panelTopCornerRadiusDp: Float,
) {
    /**
     * The overlay for the *panel*: its lower edge falls off into [panelEdgeShade]. Keys are not shaded
     * with it -- the design keeps them flat -- they get [keyShading] instead.
     *
     * [GradientDrawable] does not take one radius per corner: it wants every corner doubled into an
     * x and a y radius, eight values in total, and hands the array straight to
     * [android.graphics.Path.addRoundRect], which throws when it is shorter. Expanding the four
     * corners happens here, where that contract lives, so callers keep saying one radius per corner.
     */
    fun panelOverlay(cornerRadiiPx: FloatArray) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, panelEdgeShade)
    ).apply {
        shape = GradientDrawable.RECTANGLE
        // a corner that is a circle has the same x and y radius
        cornerRadii = eightRadii(cornerRadiiPx)
    }

    /**
     * The shading for a *key*: its flat fill plus, when the palette asks for one, the faint hairline
     * along its top inside edge. The hairline is drawn by [TopEdgeHighlightDrawable] rather than by a
     * [GradientDrawable], because a gradient can only spread three colour stops evenly over the whole
     * surface, and spreading the highlight over half a keycap is exactly the bevel the design removes.
     * [keyEdgeShade] is honoured too, so a future theme can bring the falloff back; it is 0 today.
     */
    fun keyShading(cornerRadiusPx: Float, density: Float): Drawable {
        val highlight = TopEdgeHighlightDrawable(keyTopHighlight, cornerRadiusPx, density)
        if (keyEdgeShade == 0) return highlight
        val edge = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, keyEdgeShade)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = eightRadii(floatArrayOf(cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx))
        }
        return LayerDrawable(arrayOf(edge, highlight))
    }

    private fun eightRadii(cornerRadiiPx: FloatArray) =
        FloatArray(8) { i -> cornerRadiiPx.getOrElse(i / 2) { 0f } }
}

/**
 * REA fork: the faint hairline along the top inside edge of a keycap.
 *
 * It is one design pixel tall, so it can not be a [GradientDrawable]: a gradient spreads its stops
 * over the whole surface. Drawing it clipped to the key's rounded corners keeps it from poking out
 * beside them, which a plain rectangle would do.
 */
private class TopEdgeHighlightDrawable(
    colour: Int,
    private val cornerRadiusPx: Float,
    density: Float,
) : Drawable() {
    /** one design pixel, and every length in the design maps to dp */
    private val heightPx = density
    private val paint = Paint().apply {
        color = colour
        isAntiAlias = true
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        path.reset()
        path.addRoundRect(0f, 0f, width, bounds.height().toFloat(),
            cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(0f, 0f, width, heightPx, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * REA fork: shows one end of a panel that is laid out over the height of the keyboard panel [panelView]
 * plus the [stripHeight] pixels of window below it that the navigation bar is drawn over. The window's
 * background wants the panel's last [stripHeight] pixels ([strip]), the panel's own view the rest; both
 * lay the same panel out at the same size, so the two ends are one continuous panel rather than two that
 * merely meet. The panel's height is read when it is drawn, so a panel of a different height (the emoji
 * or clipboard panels, or a changed keyboard height) is cut correctly without being told.
 *
 * The panel's view itself is only ever painted inside its own bounds, which is why the strip below it
 * needs the window's background: see [DefaultColors.setBackground].
 */
private class PanelSliceDrawable(
    private val panel: Drawable,
    private val panelView: View,
    private val stripHeight: Int,
    private val strip: Boolean,
) : Drawable() {
    override fun draw(canvas: Canvas) {
        val area = bounds
        val tall = panelView.height + stripHeight
        val top = if (strip) area.bottom - tall else area.top
        val clipTop = if (strip) area.bottom - stripHeight else area.top
        val save = canvas.save()
        canvas.clipRect(area.left, clipTop, area.right, area.bottom)
        panel.setBounds(area.left, top, area.right, top + tall)
        panel.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        panel.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        panel.colorFilter = colorFilter
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        super.setVisible(visible, restart)
        return panel.setVisible(visible, restart)
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * REA fork: how much of the window lies below the panel [view], i.e. the strip the navigation bar is
 * drawn over. Zero on a window that is not deeper than the panel, which is also what it reports before
 * the window is laid out, and zero for a panel that does not span the window -- the floating keyboard
 * is a box inside a full-screen window, and so is a keyboard that the width setting has narrowed, and
 * in both cases the panel must stay the box and the strip must stay the app behind it.
 */
private fun windowStripHeight(view: View): Int {
    val window = view.rootView
    if (view.height <= 0 || view.width < window.width)
        return 0
    val location = IntArray(2)
    view.getLocationInWindow(location)
    return (window.height - location[1] - view.height).coerceAtLeast(0)
}

class DefaultColors (
    override val themeStyle: String,
    override val hasKeyBorders: Boolean,
    private val accent: Int,
    private val background: Int,
    private val keyBackground: Int,
    private val functionalKey: Int,
    private val spaceBar: Int,
    private val keyText: Int,
    private val keyHintText: Int,
    private val suggestionText: Int = keyText,
    private val spaceBarText: Int = keyHintText,
    private val gesture: Int = accent,
    private var keyboardBackground: Drawable? = null,
    /** non-null only for themes that want translucent "glass" key surfaces; others keep opaque rendering */
    private val glassAlphas: GlassAlphas? = null,
    /** the action key (enter / search / done …) color; null means use [accent] */
    private val actionKeyBackground: Int? = null,
    /** the color a key surface takes while pressed; null means one step brighter/darker than its resting color */
    private val pressedKeyColor: Int? = null,
    /** false for themes whose design has no hint labels/icons in the key corners */
    override val showKeyHints: Boolean = true,
    /** REA fork: the panel colour drawn *over* the user background image. Null is the upstream
     *  behaviour, where the image *is* the panel; non-null makes the panel genuinely translucent
     *  over the image instead of opaque, which is what a glass design needs. */
    private val panelOverImage: Int? = null,
    /** REA fork: alpha of an extra dim layer between the background image and the panel (0 = none) */
    private val panelScrim: Int = 0,
    /** REA fork: the colour of the rules between the suggestion areas (null = keep the drawable's) */
    private val dividerColor: Int? = null,
    /** REA fork: height of those rules as a fraction of the strip height (null = keep the drawable's) */
    private val dividerHeightRatio: Float? = null,
    /** REA fork: the label of the symbols key (null = the framework's own, "?123") */
    private val symbolLabel: String? = null,
    /** REA fork: false hides the "more suggestions" hint under a suggestion */
    private val moreSuggestionsHint: Boolean = true,
    /** REA fork: soft shading drawn untinted over the panel and over every keycap (null = none) */
    private val shading: SurfaceShading? = null,
    /** REA fork: colour of the keys inside a long-press popup (null = upstream's brightened panel) */
    private val popupKeysBackground: Int? = null,
    /** REA fork: colour of the key preview bubble (null = upstream's brightened panel) */
    private val popupPreviewBackground: Int? = null,
    /** REA fork: colour of the popup panel itself (null = upstream's brightened panel) */
    private val popupPanelBackground: Int? = null,
    /** REA fork: true draws the preview as a bubble that stops at the key's top edge (see [Colors.previewBubbleAboveKey]) */
    private val previewAboveKey: Boolean = false,
    /** REA fork: colour of the strip's toolbar toggle glyph, drawn without the style's disc (null = keep the disc) */
    private val toolbarToggleGlyph: Int? = null,
    /** REA fork: width of the key preview bubble as a multiple of the key (null = the style's own size) */
    private val previewWidthRatio: Float? = null,
    /** REA fork: height of the key preview bubble above the key as a multiple of the key height (null = the style's own) */
    private val previewHeightRatio: Float? = null,
) : Colors {
    override val suggestionsDivider: Int? get() = dividerColor
    override val suggestionsDividerHeightRatio: Float? get() = dividerHeightRatio
    override val symbolKeyLabel: String? get() = symbolLabel
    override val showMoreSuggestionsHint: Boolean get() = moreSuggestionsHint
    override val previewBubbleAboveKey: Boolean get() = previewAboveKey
    override val toolbarToggleGlyphColor: Int? get() = toolbarToggleGlyph
    override val keyPreviewWidthRatio: Float? get() = previewWidthRatio
    override val keyPreviewHeightRatio: Float? get() = previewHeightRatio
    override val surfaceShading: SurfaceShading? get() = shading
    /** REA fork: only a panel that actually carries a background image wants to reach under the bar */
    override val panelUnderNavigationBar: Boolean get() = panelOverImage != null && keyboardBackground != null
    private val navBar: Int
    /** brightened or darkened variant of [background], to be used if exact background color would be
     *  bad contrast, e.g. popup keys popup or no border space bar */
    private val adjustedBackground: Int
    /** further brightened or darkened variant of [adjustedBackground] */
    private val doubleAdjustedBackground: Int
    private val adjustedSuggestionText = brightenOrDarken(suggestionText, true)

    private val backgroundFilter = colorFilter(background)
    private val adjustedBackgroundFilter: ColorFilter
    private val keyTextFilter: ColorFilter
    private val suggestionTextFilter = colorFilter(suggestionText)
    private val accentColorFilter = colorFilter(accent)

    /** color filter for the white action key icons in material theme, switches to gray if necessary for contrast */
    private val actionKeyIconColorFilter: ColorFilter?

    private val backgroundStateList: ColorStateList
    private val keyStateList: ColorStateList
    private val functionalKeyStateList: ColorStateList
    private val actionKeyStateList: ColorStateList
    private val spaceBarStateList: ColorStateList
    private val adjustedBackgroundStateList: ColorStateList
    private val stripBackgroundList: ColorStateList
    private val toolbarKeyStateList = activatedStateList(
        suggestionText,
        if (isBrightColor(suggestionText)) darken(darken(suggestionText))
        else brighten(brighten(suggestionText))
    )
    private var backgroundSetupDone = false

    /** translucent variants of the key state lists, built only when [glassAlphas] is set */
    private val glassKeyStateList: ColorStateList?
    private val glassFunctionalKeyStateList: ColorStateList?
    private val glassActionKeyStateList: ColorStateList?
    private val glassSpaceBarStateList: ColorStateList?
    /** REA fork: popup key colours the theme states itself; null keeps the upstream derived colour */
    private val popupKeyStateList: ColorStateList?
    private val glassPopupKeyStateList: ColorStateList?

    init {
        if (isDarkColor(background)) {
            adjustedBackground = brighten(background)
            doubleAdjustedBackground = brighten(adjustedBackground)
        } else {
            adjustedBackground = darken(background)
            doubleAdjustedBackground = darken(adjustedBackground)
        }
        adjustedBackgroundStateList = pressedStateList(doubleAdjustedBackground, adjustedBackground)

        val stripBackground: Int
        val pressedStripElementBackground: Int
        if (keyboardBackground != null || (themeStyle == STYLE_HOLO && hasKeyBorders)) {
            stripBackground = Color.TRANSPARENT
            pressedStripElementBackground = if (isDarkColor(background)) 0x22ffffff // assume background is similar to the background color
                else 0x11000000
        } else if (hasKeyBorders) {
            stripBackground = background
            pressedStripElementBackground = adjustedBackground
        } else {
            stripBackground = adjustedBackground
            pressedStripElementBackground = doubleAdjustedBackground
        }
        stripBackgroundList = pressedStateList(pressedStripElementBackground, stripBackground)

        if (themeStyle == STYLE_HOLO && keyboardBackground == null) {
            val darkerBackground = adjustLuminosityAndKeepAlpha(background, -0.2f)
            navBar = darkerBackground
            keyboardBackground = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(background, darkerBackground))
            backgroundSetupDone = true
        } else {
            navBar = background
        }

        adjustedBackgroundFilter = colorFilter(adjustedBackground)
        val actionKey = actionKeyBackground ?: accent
        // [pressed, normal] for each surface, so the opaque list and (for glass themes) the translucent
        // one are built from exactly the same colors and cannot drift apart.
        val keyColors: IntArray
        val functionalKeyColors: IntArray
        val actionKeyColors: IntArray
        val spaceBarColors: IntArray
        if (hasKeyBorders) {
            backgroundStateList = pressedStateList(brightenOrDarken(background, true), background)
            keyColors = if (themeStyle == STYLE_HOLO) intArrayOf(keyBackground, keyBackground)
                else intArrayOf(pressedVariant(keyBackground), keyBackground)
            functionalKeyColors = intArrayOf(pressedVariant(functionalKey), functionalKey)
            actionKeyColors = if (themeStyle == STYLE_HOLO) functionalKeyColors
                else intArrayOf(pressedVariant(actionKey), actionKey)
            spaceBarColors = if (themeStyle == STYLE_HOLO) intArrayOf(spaceBar, spaceBar)
                else intArrayOf(pressedVariant(spaceBar), spaceBar)
        } else {
            // need to set color to background if key borders are disabled, or there will be ugly keys
            backgroundStateList = pressedStateList(brightenOrDarken(background, true), background)
            keyColors = intArrayOf(keyBackground, Color.TRANSPARENT)
            functionalKeyColors = keyColors
            actionKeyColors = if (themeStyle == STYLE_HOLO) functionalKeyColors
                else intArrayOf(pressedVariant(actionKey), actionKey)
            spaceBarColors = intArrayOf(pressedVariant(spaceBar), spaceBar)
        }
        keyStateList = pressedStateList(keyColors[0], keyColors[1])
        functionalKeyStateList = pressedStateList(functionalKeyColors[0], functionalKeyColors[1])
        actionKeyStateList = pressedStateList(actionKeyColors[0], actionKeyColors[1])
        spaceBarStateList = pressedStateList(spaceBarColors[0], spaceBarColors[1])
        keyTextFilter = colorFilter(keyText)
        actionKeyIconColorFilter = when {
            themeStyle == STYLE_HOLO -> keyTextFilter
            // the white icon may not have enough contrast, and can't be adjusted by the user
            isBrightColor(actionKey) -> colorFilter(Color.DKGRAY)
            else -> null
        }

        // Translucent copies of the key lists, for themes that fake glass. Built from the same
        // [pressed, normal] colors as the opaque lists, so the no-key-borders case (transparent
        // character keys) and all the pressed colors stay identical and only opacity differs.
        glassKeyStateList = glassAlphas?.let { glass ->
            pressedStateList(keyColors[0].scaledAlpha(glass.keyPressed), keyColors[1].scaledAlpha(glass.key))
        }
        glassFunctionalKeyStateList = glassAlphas?.let { glass ->
            pressedStateList(
                functionalKeyColors[0].scaledAlpha(glass.functionalKeyPressed),
                functionalKeyColors[1].scaledAlpha(glass.functionalKey)
            )
        }
        glassActionKeyStateList = glassAlphas?.let { glass ->
            pressedStateList(
                actionKeyColors[0].scaledAlpha(glass.functionalKeyPressed),
                actionKeyColors[1].scaledAlpha(glass.functionalKey)
            )
        }
        glassSpaceBarStateList = glassAlphas?.let { glass ->
            pressedStateList(spaceBarColors[0].scaledAlpha(glass.keyPressed), spaceBarColors[1].scaledAlpha(glass.key))
        }

        // REA fork: a theme may state the colour of popup keys itself. They then get the same
        // [pressed, normal] structure and the same glass alphas as character keys, so a long-press
        // popup looks like the keyboard it opened from instead of a brightened version of the panel.
        popupKeyStateList = popupKeysBackground?.let { pressedStateList(pressedVariant(it), it) }
        glassPopupKeyStateList = if (popupKeysBackground != null) glassAlphas?.let { glass ->
            pressedStateList(
                popupKeysBackground.scaledAlpha(glass.keyPressed),
                popupKeysBackground.scaledAlpha(glass.key)
            )
        } else null
    }

    /** the pressed variant of a key surface color: the theme's own pressed color if it states one,
     *  otherwise one step brighter/darker than the resting color (the classic HeliBoard look).
     *
     *  The theme's pressed color is taken at the opacity of the key it presses, never below it: a
     *  pressed colour more transparent than the resting key would *dim* an opaque key (the action
     *  key) instead of lighting it up, and a design's pressed state is lighter and more opaque. */
    private fun pressedVariant(color: Int) = pressedKeyColor?.let {
        ColorUtils.setAlphaComponent(it, maxOf(Color.alpha(it), Color.alpha(color)))
    } ?: brightenOrDarken(color, true)

    override fun get(color: ColorType): Int = when (color) {
        ACTION_KEY_BACKGROUND -> actionKeyBackground ?: accent
        TOOL_BAR_KEY_ENABLED_BACKGROUND, EMOJI_CATEGORY_SELECTED, CLIPBOARD_PIN -> accent
        SHIFT_KEY_ICON -> if (accentShiftedIcon) accent else keyText
        AUTOFILL_BACKGROUND_CHIP -> if (themeStyle == STYLE_MATERIAL && !hasKeyBorders) background else adjustedBackground
        GESTURE_PREVIEW, MORE_SUGGESTIONS_BACKGROUND, KEY_PREVIEW_BACKGROUND -> adjustedBackground
        POPUP_KEYS_BACKGROUND -> popupKeysBackground ?: adjustedBackground
        TOOL_BAR_EXPAND_KEY_BACKGROUND, CLIPBOARD_SUGGESTION_BACKGROUND -> doubleAdjustedBackground
        GESTURE_TRAIL -> gesture
        KEY_TEXT, REMOVE_SUGGESTION_ICON, FUNCTIONAL_KEY_TEXT, KEY_ICON, EMOJI_KEY_TEXT,
            POPUP_KEY_TEXT, POPUP_KEY_ICON, KEY_PREVIEW_TEXT, EMOJI_SEARCH_TEXT, CLIPBOARD_SUGGESTION_ICON -> keyText
        KEY_HINT_TEXT -> keyHintText
        SPACE_BAR_TEXT -> spaceBarText
        FUNCTIONAL_KEY_BACKGROUND, EMOJI_SEARCH_BACKGROUND -> functionalKey
        SPACE_BAR_BACKGROUND -> spaceBar
        MORE_SUGGESTIONS_WORD_BACKGROUND, MAIN_BACKGROUND -> background
        KEY_BACKGROUND -> keyBackground
        ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackground else (actionKeyBackground ?: accent)
        STRIP_BACKGROUND -> if (!hasKeyBorders && themeStyle == STYLE_MATERIAL) adjustedBackground else background
        NAVIGATION_BAR -> navBar
        SUGGESTION_AUTO_CORRECT, EMOJI_CATEGORY, TOOL_BAR_KEY, TOOL_BAR_EXPAND_KEY, ONE_HANDED_MODE_BUTTON -> suggestionText
        MORE_SUGGESTIONS_HINT, SUGGESTED_WORD, SUGGESTION_TYPED_WORD, SUGGESTION_VALID_WORD -> adjustedSuggestionText
        ACTION_KEY_ICON -> Color.WHITE
    }

    override fun setColor(drawable: Drawable, color: ColorType) {
        // Glass themes replace the tint instead of multiplying it. MULTIPLY composites alpha as
        // Sa + Da - Sa*Da, so an opaque drawable stays opaque whatever alpha the tint carries.
        // SRC_IN composites as Sa * Da, so the tint alpha becomes real translucency and the
        // drawable's alpha (its rounded corners, and the transparent empty-key state) masks the
        // tint. Only the tint's color survives SRC_IN, so the baked color ramp in the drawable
        // does not; the surface becomes one flat translucent color per state.
        val glassStateList = when (color) {
            KEY_BACKGROUND -> glassKeyStateList
            FUNCTIONAL_KEY_BACKGROUND -> glassFunctionalKeyStateList
            ACTION_KEY_BACKGROUND -> glassActionKeyStateList
            SPACE_BAR_BACKGROUND -> glassSpaceBarStateList
            POPUP_KEYS_BACKGROUND -> glassPopupKeyStateList
            else -> null
        }
        if (glassStateList != null) {
            DrawableCompat.setTintMode(drawable, PorterDuff.Mode.SRC_IN)
            DrawableCompat.setTintList(drawable, glassStateList)
            return
        }
        val colorStateList = when (color) {
            MORE_SUGGESTIONS_WORD_BACKGROUND -> backgroundStateList
            KEY_BACKGROUND -> keyStateList
            FUNCTIONAL_KEY_BACKGROUND -> functionalKeyStateList
            ACTION_KEY_BACKGROUND -> actionKeyStateList
            SPACE_BAR_BACKGROUND -> spaceBarStateList
            POPUP_KEYS_BACKGROUND -> popupKeyStateList ?: adjustedBackgroundStateList
            STRIP_BACKGROUND -> stripBackgroundList
            ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackgroundStateList
                else actionKeyStateList
            TOOL_BAR_KEY -> toolbarKeyStateList
            else -> null // use color filter
        }
        if (colorStateList == null) {
            drawable.colorFilter = getColorFilter(color)
            return
        }
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.MULTIPLY)
        DrawableCompat.setTintList(drawable, colorStateList)
    }

    override fun setColor(view: ImageView, color: ColorType) {
        if (color == TOOL_BAR_KEY) {
            setColor(view.drawable, color)
            return
        }
        view.colorFilter = getColorFilter(color)
    }

    override fun setBackground(view: View, color: ColorType) {
        if (view.background == null)
            view.setBackgroundColor(Color.WHITE) // set white to make the color filters work
        when (color) {
            KEY_PREVIEW_BACKGROUND -> {
                val bubble = popupPreviewBackground
                if (bubble == null) view.background.colorFilter = adjustedBackgroundFilter
                else keepingPadding(view) { view.background = keyPreviewBubble(view, bubble, view.paddingBottom) }
            }
            POPUP_KEYS_BACKGROUND -> {
                val panel = popupPanelBackground
                val style = view.background
                // the style gives the action-key popup no panel of its own; keep it without one
                if (panel == null || style == null || isTransparent(style))
                    style?.colorFilter = if (panel != null) colorFilter(panel) else adjustedBackgroundFilter
                else keepingPadding(view) { view.background = roundedSurface(view, panel) }
            }
            FUNCTIONAL_KEY_BACKGROUND, KEY_BACKGROUND, MORE_SUGGESTIONS_WORD_BACKGROUND, SPACE_BAR_BACKGROUND, STRIP_BACKGROUND, CLIPBOARD_SUGGESTION_BACKGROUND -> setColor(view.background, color)
            ONE_HANDED_MODE_BUTTON -> setColor(view.background, if (keyboardBackground == null) MAIN_BACKGROUND else STRIP_BACKGROUND)
            MORE_SUGGESTIONS_BACKGROUND -> view.background.colorFilter = backgroundFilter
            MAIN_BACKGROUND -> {
                // rasterizing needs a laid-out panel to know how big the bitmap has to be, so it is
                // retried on the next call while the panel has no size yet (see panelBackgroundOrNull
                // for this and the undecodable-image case). The source image is only replaced once it
                // really became a panel-sized bitmap.
                val image = keyboardBackground
                val panel = when {
                    image == null -> null
                    backgroundSetupDone -> image
                    else -> panelBackgroundOrNull(image, view, panelOverImage, panelScrim, shading)?.also {
                        keyboardBackground = it
                        backgroundSetupDone = true
                    }
                }
                if (panel != null) {
                    // REA fork: this theme's panel is the window's own surface, and that surface is
                    // taller than the panel view by the strip the navigation bar is drawn over. A
                    // background is only painted inside its own view, so the panel is laid out over its
                    // height plus that strip and its last pixels are painted into the window's
                    // background, which is the only thing covering the strip (see PanelSliceDrawable).
                    // Nothing about the layout changes: the keys keep their size and position, the panel
                    // simply no longer stops one bar short of the bottom of the screen.
                    val strip = windowStripHeight(view)
                    view.background = PanelSliceDrawable(panel, view, strip, strip = false)
                    view.rootView.background = if (strip > 0)
                        PanelSliceDrawable(panel, view, strip, strip = true) else null
                } else if (panelOverImage != null && image == null) {
                    // REA fork: no background image, yet this theme's panel is translucent material of
                    // its own. Painting it as a layer instead of tinting the view's white background
                    // gives the panel its soft shading too, and keeps both cases identical.
                    view.background = panelSurface(null, panelOverImage, 0, shading, view)
                } else {
                    // no background image (yet): the panel is the theme's flat panel color
                    view.background.colorFilter = backgroundFilter
                }
            }
            else -> view.background.colorFilter = backgroundFilter
        }
    }

    override fun setKeyPreviewBackground(view: View, tail: Int) {
        val bubble = popupPreviewBackground
        if (bubble == null) setBackground(view, ColorType.KEY_PREVIEW_BACKGROUND)
        else keepingPadding(view) { view.background = keyPreviewBubble(view, bubble, tail) }
    }

    /** REA fork: the key preview of a theme that states its own bubble colour: a plain rounded bubble
     *  that sits above the key it previews.
     *
     *  The style's own drawable is a rounded rectangle whose bottom padding (60dp in the Material
     *  style) upstream leaves filled and lets the long-press panel be placed against inside it, so
     *  the key preview reads as a neck between the panel and the key. The theme's bubble keeps the
     *  style's size and padding - they are what positions the text and the bubble - but paints at
     *  most down to the key's top edge ([tail] is the part of the preview's bounds below it), so no
     *  neck hangs between the panel and the key.
     */
    private fun keyPreviewBubble(view: View, fill: Int, tail: Int): Drawable {
        val style = view.background
        val density = view.resources.displayMetrics.density
        val intrinsicWidth = style?.intrinsicWidth?.takeIf { it > 0 } ?: (35 * density).toInt()
        val intrinsicHeight = style?.intrinsicHeight?.takeIf { it > 0 } ?: (5 * density).toInt()
        val stylePadding = Rect()
        style?.getPadding(stylePadding)
        return TailClippedSurface(
            roundedSurface(view, fill), tail, stylePadding, intrinsicWidth, intrinsicHeight)
    }

    /** REA fork: a flat rounded surface in a colour the theme states and with the corner radius the
     *  theme's keys use. It is used where a theme paints a surface itself instead of filtering the
     *  style's own drawable, so the style's shadows and its own radius do not show through. */
    private fun roundedSurface(view: View, fill: Int): GradientDrawable {
        val radius = (shading?.keyCornerRadiusDp ?: 0f) * view.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
        }
    }

    /** REA fork: replacing a background that carried the view's insets must not move the view's
     *  content, so the padding the style's drawable gave the view is captured and put back. */
    private inline fun keepingPadding(view: View, replace: () -> Unit) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        replace()
        view.setPadding(left, top, right, bottom)
    }

    /** a drawable that paints nothing, e.g. the panel style the action key popup is given */
    private fun isTransparent(drawable: Drawable) =
        drawable is ColorDrawable && Color.alpha(drawable.color) == 0

    private fun getColorFilter(color: ColorType): ColorFilter? = when (color) {
        EMOJI_CATEGORY_SELECTED, CLIPBOARD_PIN -> accentColorFilter
        SHIFT_KEY_ICON -> if (accentShiftedIcon) accentColorFilter else keyTextFilter
        KEY_TEXT, KEY_ICON -> keyTextFilter
        REMOVE_SUGGESTION_ICON, EMOJI_CATEGORY, ONE_HANDED_MODE_BUTTON, TOOL_BAR_KEY, TOOL_BAR_EXPAND_KEY -> suggestionTextFilter
        KEY_PREVIEW_BACKGROUND -> adjustedBackgroundFilter
        ACTION_KEY_ICON -> actionKeyIconColorFilter
        else -> colorFilter(get(color)) // create color filter (not great for performance, so the frequently used filters should be stored)
    }
}

class AllColors(private val colorMap: EnumMap<ColorType, Int>, override val themeStyle: String, override val hasKeyBorders: Boolean, backgroundImage: Drawable?) : Colors {
    private var keyboardBackground: Drawable? = backgroundImage
    private val stateListMap = EnumMap<ColorType, ColorStateList>(ColorType::class.java)
    private var backgroundSetupDone = false
    private val colorFilters = hashMapOf<ColorType, ColorFilter>()
    override fun get(color: ColorType): Int = colorMap[color] ?: color.default()

    override fun setColor(drawable: Drawable, color: ColorType) {
        val colorStateList = stateListMap.getOrPut(color) { pressedStateList(brightenOrDarken(get(color), true), get(color)) }
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.MULTIPLY)
        DrawableCompat.setTintList(drawable, colorStateList)
    }

    override fun setColor(view: ImageView, color: ColorType) {
        if (color == TOOL_BAR_KEY) {
            setColor(view.drawable, color)
            return
        }
        view.colorFilter = getColorFilter(color)
    }

    override fun setBackground(view: View, color: ColorType) {
        if (view.background == null)
            view.setBackgroundColor(Color.WHITE) // set white to make the color filters work
        when (color) {
            ONE_HANDED_MODE_BUTTON -> setColor(view.background, MAIN_BACKGROUND) // button has no separate background color
            MAIN_BACKGROUND -> {
                // rasterizing needs a laid-out panel to know how big the bitmap has to be, so it is
                // retried on the next call while the panel has no size yet (see panelBackgroundOrNull
                // for this and the undecodable-image case). The source image is only replaced once it
                // really became a panel-sized bitmap.
                val image = keyboardBackground
                val panel = when {
                    image == null -> null
                    backgroundSetupDone -> image
                    else -> panelBackgroundOrNull(image, view)?.also {
                        keyboardBackground = it
                        backgroundSetupDone = true
                    }
                }
                if (panel != null) view.background = panel
                else setColor(view.background, color)
            }
            else -> setColor(view.background, color)
        }
    }

    private fun getColorFilter(color: ColorType) = colorFilters.getOrPut(color) { colorFilter(get(color)) }
}

private fun colorFilter(color: Int, mode: BlendModeCompat = BlendModeCompat.MODULATE): ColorFilter {
    // using !! for the color filter because null is only returned for unsupported blend modes, which are not used
    return BlendModeColorFilterCompat.createBlendModeColorFilterCompat(color, mode)!!
}

private fun pressedStateList(pressed: Int, normal: Int): ColorStateList {
    val states = arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(-android.R.attr.state_pressed))
    return ColorStateList(states, intArrayOf(pressed, normal))
}

private fun activatedStateList(activated: Int, normal: Int): ColorStateList {
    val states = arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf(-android.R.attr.state_activated))
    return ColorStateList(states, intArrayOf(activated, normal))
}

/**
 * Rasterize a user background image to the exact size of the keyboard panel [view], or return null
 * if the panel can not use it. Null means "use the flat panel color for now" and happens when:
 *  - the panel is not laid out yet. A panel-sized bitmap is all we need, so rasterizing before the
 *    size is known would either crash (width and height must be > 0) or bake in a stale size that
 *    the view then draws distorted. Callers keep the source image in this case and try again on
 *    their next call, so the first keyboard show - which happens before the panel is measured -
 *    does not throw the image away.
 *  - the image file exists but does not decode. BitmapFactory returns null for unreadable or
 *    unsupported data instead of throwing, and BitmapDrawable stores that null without complaint,
 *    so it has to be caught here or it crashes inside [toBitmap]. Retrying such an image keeps
 *    returning null, i.e. the panel simply stays flat.
 *
 * The resulting bitmap is stretched to the panel, ignoring its aspect ratio: there is no cropping.
 */
private fun panelBackgroundOrNull(
    image: Drawable,
    view: View,
    panelOverImage: Int? = null,
    panelScrim: Int = 0,
    shading: SurfaceShading? = null,
): Drawable? {
    if (view.width <= 0 || view.height <= 0) return null
    if (image is BitmapDrawable && image.bitmap == null) return null
    val rasterised = image.toBitmap(view.width, view.height).toDrawable(view.context.resources)
    if (panelOverImage == null) return rasterised
    return panelSurface(rasterised, panelOverImage, panelScrim, shading, view)
}

/**
 * REA fork: the panel as a stack of layers: the background image (if there is one), an optional dim
 * layer, the panel colour, and the theme's soft shading on top.
 *
 * The shading is the top layer and is never tinted, because the tint is what paints the layers
 * below (see [SurfaceShading]). Only themes that state a [DefaultColors] panel colour over the image
 * get this; for every other theme the panel is still the image, or one flat colour.
 */
private fun panelSurface(
    image: Drawable?,
    panelColour: Int,
    scrim: Int,
    shading: SurfaceShading?,
    view: View,
): Drawable {
    val layers = ArrayList<Drawable>(4)
    image?.let { layers.add(it) }
    if (scrim > 0) layers.add(ColorDrawable(ColorUtils.setAlphaComponent(panelColour, scrim)))
    layers.add(ColorDrawable(panelColour))
    shading?.let {
        val radius = it.panelTopCornerRadiusDp * view.resources.displayMetrics.density
        layers.add(it.panelOverlay(floatArrayOf(radius, radius, 0f, 0f)))
    }
    return LayerDrawable(layers.toTypedArray())
}

/**
 * This color with its alpha scaled by [factor] (out of 255). Used to derive the translucent "glass"
 * variants of the key colors from the opaque ones, so the only difference between a glass theme and
 * a normal one is opacity.
 *
 * Scaling rather than overwriting matters: a surface color may already carry a deliberate
 * alpha, and that must keep its relative transparency. Fully transparent colors stay
 * transparent.
 */
internal fun Int.scaledAlpha(factor: Int): Int =
    if (Color.alpha(this) == 0) this
    else ColorUtils.setAlphaComponent(this, Color.alpha(this) * factor / 255)

enum class ColorType {
    ACTION_KEY_ICON,
    ACTION_KEY_BACKGROUND,
    ACTION_KEY_POPUP_KEYS_BACKGROUND,
    AUTOFILL_BACKGROUND_CHIP,
    CLIPBOARD_PIN,
    EMOJI_CATEGORY,
    EMOJI_CATEGORY_SELECTED,
    EMOJI_KEY_TEXT,
    EMOJI_SEARCH_TEXT,
    EMOJI_SEARCH_BACKGROUND,
    FUNCTIONAL_KEY_TEXT,
    FUNCTIONAL_KEY_BACKGROUND,
    GESTURE_TRAIL,
    GESTURE_PREVIEW,
    KEY_BACKGROUND,
    KEY_ICON,
    KEY_TEXT,
    KEY_HINT_TEXT,
    KEY_PREVIEW_BACKGROUND,
    KEY_PREVIEW_TEXT,
    MORE_SUGGESTIONS_HINT,
    MORE_SUGGESTIONS_BACKGROUND,
    MORE_SUGGESTIONS_WORD_BACKGROUND,
    POPUP_KEYS_BACKGROUND,
    POPUP_KEY_TEXT,
    POPUP_KEY_ICON,
    NAVIGATION_BAR,
    SHIFT_KEY_ICON,
    SPACE_BAR_BACKGROUND,
    SPACE_BAR_TEXT,
    ONE_HANDED_MODE_BUTTON,
    REMOVE_SUGGESTION_ICON,
    STRIP_BACKGROUND,
    CLIPBOARD_SUGGESTION_BACKGROUND,
    CLIPBOARD_SUGGESTION_ICON,
    SUGGESTED_WORD,
    SUGGESTION_AUTO_CORRECT,
    SUGGESTION_TYPED_WORD,
    SUGGESTION_VALID_WORD,
    TOOL_BAR_EXPAND_KEY,
    TOOL_BAR_EXPAND_KEY_BACKGROUND,
    TOOL_BAR_KEY,
    TOOL_BAR_KEY_ENABLED_BACKGROUND,
    MAIN_BACKGROUND,
}

// this is not used any more, but we keep in case a colorMap does not get filled for whatever reason
fun ColorType.default() = ColorUtils.setAlphaComponent(name.hashCode() and 0xffffff, 255)

/**
 * A rounded surface that fills only the part of its bounds that lies above [tail] pixels from its
 * bottom, and paints nothing below that. It is the flat bubble a theme that states its own key
 * preview colour draws where the style's own drawable would stretch down to the key it previews.
 *
 * Size and padding are the style drawable's, so a wrap_content view that is measured against it -
 * and then re-measured on every reuse - keeps its own size and the popup panel keeps its position:
 * only the part below the key's top edge is not painted.
 */
private class TailClippedSurface(
    private val surface: GradientDrawable,
    private val tail: Int,
    private val stylePadding: Rect,
    private val intrinsicWidth: Int,
    private val intrinsicHeight: Int
) : Drawable() {
    override fun draw(canvas: Canvas) {
        surface.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom - tail)
        surface.draw(canvas)
    }

    override fun getPadding(padding: Rect): Boolean {
        padding.set(stylePadding)
        return true
    }

    override fun setAlpha(alpha: Int) {
        surface.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        surface.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = surface.opacity

    override fun getIntrinsicWidth(): Int = intrinsicWidth

    override fun getIntrinsicHeight(): Int = intrinsicHeight
}
