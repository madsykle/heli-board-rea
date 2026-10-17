/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.AllColors
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.DefaultColors
import helium314.keyboard.latin.common.DynamicColors
import helium314.keyboard.latin.common.GlassAlphas
import helium314.keyboard.latin.common.SurfaceShading
import helium314.keyboard.latin.common.scaledAlpha
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.brightenOrDarken
import helium314.keyboard.latin.utils.isBrightColor
import helium314.keyboard.latin.utils.isGoodContrast
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.EnumMap
import androidx.core.graphics.toColorInt

/**
 * REA fork: the whole "iOS 27 Dark" palette, in one place.
 *
 * Every colour the theme uses is named here, so tuning it is a one-line edit in this object and
 * nothing else has to be touched. The values are the design's own, written as ARGB: the alpha is
 * part of the colour, so translucent surfaces are written as e.g. `#CC202127` rather than as an
 * opaque colour plus a separate opacity.
 *
 * The three scalars at the end are deliberately separate knobs for how much of what sits behind the
 * keyboard shows through the panel and the keys. They scale the alphas written above (255 = use them
 * exactly as written), so both the colour and the transparency of a surface stay tunable in one place.
 *
 * Only [KeyboardTheme.THEME_IOS27] reads this; every other theme keeps its own colours untouched.
 */
object Ios27Palette {
    // ---- surfaces -------------------------------------------------------------------------------
    /** the keyboard panel; the suggestion strip is part of the same slab and draws nothing of its own */
    val PANEL = 0xCC202127.toInt()
    /** character keys and the spacebar */
    val KEY = 0x665B5F68.toInt()
    /** any key surface while it is held down */
    val PRESSED = 0x997D828D.toInt()
    /** functional keys: shift, delete, globe, mode keys */
    val MODIFIER = 0x44333842.toInt()
    /** the action key: enter, search, done, send */
    val ACTION = 0xFF626570.toInt()
    /** the rules between the suggestion areas */
    val DIVIDER = 0x30FFFFFF.toInt()
    // ---- text and icons -------------------------------------------------------------------------
    /** key letters and suggestion text */
    val CHARACTER = 0xFFF5F5F7.toInt()
    /** everything secondary: corner hints, toolbar icons, spacebar label */
    val SECONDARY_ICON = 0xB8C0C9.toInt()
    // ---- alphas, applied on top of the colours above --------------------------------------------
    /** how opaque the panel material is; 255 keeps `#CC202127`, lower shows more of the backdrop */
    const val PANEL_ALPHA = 255
    /** how opaque every key surface is; 255 keeps the alphas above, lower shows more of the backdrop */
    const val KEY_ALPHA = 255
    /** how strongly an extra dim layer sits between the background image and the panel; 0 = none */
    const val SCRIM_INTENSITY = 0
    // ---- soft shading ---------------------------------------------------------------------------
    /** the soft darkening that falls off from the lower edge of the panel and of every keycap */
    const val EDGE_SHADE = 0x33000000
    /** the faint highlight along the top inside edge of every keycap */
    const val TOP_HIGHLIGHT = 0x2BFFFFFF
    /** corner radius of a keycap, in dp: keys, popup keys and emoji keys all use it */
    const val KEY_RADIUS_DP = 9f
    /** corner radius of the top corners of the panel, in dp */
    const val PANEL_RADIUS_DP = 28f

    /** the panel colour with [PANEL_ALPHA] applied; this is what the theme actually paints */
    val panel: Int get() = PANEL.scaledAlpha(PANEL_ALPHA)
    /** a character key with [KEY_ALPHA] applied */
    val key: Int get() = KEY.scaledAlpha(KEY_ALPHA)
    /** a pressed key with [KEY_ALPHA] applied */
    val pressed: Int get() = PRESSED.scaledAlpha(KEY_ALPHA)
    /** a functional key with [KEY_ALPHA] applied */
    val modifier: Int get() = MODIFIER.scaledAlpha(KEY_ALPHA)
    /** the action key with [KEY_ALPHA] applied */
    val action: Int get() = ACTION.scaledAlpha(KEY_ALPHA)

    /** the shading overlay for keys and panel, described once and used by both */
    val shading = SurfaceShading(
        edgeShade = EDGE_SHADE,
        topHighlight = TOP_HIGHLIGHT,
        keyCornerRadiusDp = KEY_RADIUS_DP,
        panelTopCornerRadiusDp = PANEL_RADIUS_DP
    )
}

class KeyboardTheme // Note: The themeId should be aligned with "themeId" attribute of Keyboard style in values/themes-<style>.xml.
private constructor(val themeId: Int, @JvmField val mStyleId: Int) {
    override fun equals(other: Any?) = if (other === this) true
        else (other as? KeyboardTheme)?.themeId == themeId

    override fun hashCode(): Int {
        return themeId
    }

    companion object {
        // old themes, now called styles
        const val STYLE_MATERIAL = "Material"
        const val STYLE_HOLO = "Holo"
        const val STYLE_ROUNDED = "Rounded"

        // new themes that are just colors
        const val THEME_LIGHT = "light"
        const val THEME_HOLO_WHITE = "holo_white"
        const val THEME_DARK = "dark"
        const val THEME_DARKER = "darker"
        const val THEME_BLACK = "black"
        const val THEME_DYNAMIC = "dynamic"
        const val THEME_BLUE_GRAY = "blue_gray"
        const val THEME_BROWN = "brown"
        const val THEME_CHOCOLATE = "chocolate"
        const val THEME_CLOUDY = "cloudy"
        const val THEME_FOREST = "forest"
        const val THEME_INDIGO = "indigo"
        const val THEME_OCEAN = "ocean"
        const val THEME_PINK = "pink"
        const val THEME_SAND = "sand"
        const val THEME_VIOLETTE = "violette"
        // REA fork additions. All are dark palettes so they are offered day and night.
        const val THEME_MIDNIGHT = "rea_midnight"
        const val THEME_NORD = "rea_nord"
        const val THEME_GRUVBOX = "rea_gruvbox"
        const val THEME_SOLARIZED = "rea_solarized"
        const val THEME_TERMINAL = "rea_terminal"
        const val THEME_SUNSET = "rea_sunset"
        // REA fork: palette ported from the maintainer's Figma "iOS 27 Dark" design.
        // The values are the *rendered* colours, i.e. the design's translucent layers
        // already composited onto the panel behind them, because an IME cannot blur.
        const val THEME_IOS27 = "rea_ios27"
        fun getAvailableDefaultColors(prefs: SharedPreferences, isNight: Boolean) = listOfNotNull(
            if (!isNight) THEME_LIGHT else null, THEME_DARK,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) THEME_DYNAMIC else null,
            if (prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE) == STYLE_HOLO) THEME_HOLO_WHITE else null,
            THEME_DARKER,
            THEME_BLACK,
            if (!isNight) THEME_BLUE_GRAY else null,
            if (!isNight) THEME_BROWN else null,
            THEME_CHOCOLATE,
            THEME_CLOUDY,
            THEME_FOREST,
            if (!isNight) THEME_INDIGO else null,
            if (!isNight) THEME_PINK else null,
            THEME_OCEAN,
            if (!isNight) THEME_SAND else null,
            THEME_VIOLETTE,
            THEME_IOS27,
            THEME_MIDNIGHT,
            THEME_NORD,
            THEME_GRUVBOX,
            THEME_SOLARIZED,
            THEME_TERMINAL,
            THEME_SUNSET
        )
        val STYLES = arrayOf(STYLE_MATERIAL, STYLE_HOLO, STYLE_ROUNDED)

        // These should be aligned with Keyboard.themeId and Keyboard.Case.keyboardTheme
        // attributes' values in attrs.xml.
        private const val THEME_ID_HOLO_BASE = 0
        private const val THEME_ID_LXX_BASE = 1
        private const val THEME_ID_LXX_BASE_BORDER = 2
        private const val THEME_ID_ROUNDED_BASE = 3
        private const val THEME_ID_ROUNDED_BASE_BORDER = 4
        private const val DEFAULT_THEME_ID = THEME_ID_LXX_BASE

        private val KEYBOARD_THEMES = arrayOf(
            KeyboardTheme(THEME_ID_HOLO_BASE, R.style.KeyboardTheme_HoloBase),
            KeyboardTheme(THEME_ID_LXX_BASE, R.style.KeyboardTheme_LXX_Base),
            KeyboardTheme(THEME_ID_LXX_BASE_BORDER, R.style.KeyboardTheme_LXX_Base_Border),
            KeyboardTheme(THEME_ID_ROUNDED_BASE, R.style.KeyboardTheme_Rounded_Base),
            KeyboardTheme(THEME_ID_ROUNDED_BASE_BORDER, R.style.KeyboardTheme_Rounded_Base_Border)
        )

        // named colors, with names from old settings
        const val COLOR_ACCENT = "accent"
        const val COLOR_GESTURE = "gesture"
        const val COLOR_SUGGESTION_TEXT = "suggestion_text"
        const val COLOR_TEXT = "text"
        const val COLOR_HINT_TEXT = "hint_text"
        const val COLOR_KEYS = "keys"
        const val COLOR_FUNCTIONAL_KEYS = "functional_keys"
        const val COLOR_SPACEBAR = "spacebar"
        const val COLOR_SPACEBAR_TEXT = "spacebar_text"
        const val COLOR_BACKGROUND = "background"

        @JvmStatic
        fun getKeyboardTheme(context: Context): KeyboardTheme {
            val prefs = context.prefs()
            val style = prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)
            val borders = prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)
            val matchingId = when (style) {
                STYLE_HOLO -> THEME_ID_HOLO_BASE
                STYLE_ROUNDED -> if (borders) THEME_ID_ROUNDED_BASE_BORDER else THEME_ID_ROUNDED_BASE
                else -> if (borders) THEME_ID_LXX_BASE_BORDER else THEME_ID_LXX_BASE
            }
            return KEYBOARD_THEMES.firstOrNull { it.themeId == matchingId } ?: KEYBOARD_THEMES[DEFAULT_THEME_ID]
        }

        fun getThemeActionAndEmojiKeyLabelFlags(themeId: Int): Int {
            return if (themeId == THEME_ID_LXX_BASE || themeId == THEME_ID_ROUNDED_BASE) Key.LABEL_FLAGS_KEEP_BACKGROUND_ASPECT_RATIO else 0
        }

        /** Whether the night colours apply right now, honouring the preview of the settings app. */
        private fun isNight(context: Context, prefs: SharedPreferences) = SettingsActivity.forceNight
            ?: (ResourceUtils.isNight(context.resources) && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT))

        /**
         * Name of the colour theme that is in use right now, honouring the theme preview of the
         * settings app ([SettingsActivity.forceTheme] / [SettingsActivity.forceNight]).
         *
         * REA fork: a theme's design is more than its colours, so this is also what the parts that
         * are not in [Colors] look up - the bundled font and the icon set of iOS 27 Dark.
         */
        @JvmStatic
        fun getCurrentColorThemeName(context: Context): String {
            val prefs = context.prefs()
            val themeName = SettingsActivity.forceTheme ?: if (isNight(context, prefs))
                prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
            else
                prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
            return themeName!!
        }

        @JvmStatic
        fun getColorsForCurrentTheme(context: Context): Colors {
            val prefs = context.prefs()
            val themeName = getCurrentColorThemeName(context)
            val themeStyle = prefs.getString(Settings.PREF_THEME_STYLE, Defaults.PREF_THEME_STYLE)

            return getThemeColors(themeName, themeStyle!!, context, prefs, isNight(context, prefs))
        }

        private fun getThemeColors(themeName: String, themeStyle: String, context: Context, prefs: SharedPreferences, isNight: Boolean): Colors {
            val hasBorders = prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)
            val backgroundImage = Settings.readUserBackgroundImage(context, isNight)
            return when (themeName) {
                THEME_DYNAMIC -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) DynamicColors(context, themeStyle, hasBorders, backgroundImage)
                    else getThemeColors(THEME_LIGHT, themeStyle, context, prefs, isNight)
                }
                THEME_LIGHT -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    ContextCompat.getColor(context, R.color.gesture_trail_color_lxx_light),
                    ContextCompat.getColor(context, R.color.keyboard_background_lxx_light_border),
                    ContextCompat.getColor(context, R.color.key_background_normal_lxx_light_border),
                    ContextCompat.getColor(context, R.color.key_background_functional_lxx_light_border),
                    ContextCompat.getColor(context, R.color.key_background_normal_lxx_light_border),
                    ContextCompat.getColor(context, R.color.key_text_color_lxx_light),
                    ContextCompat.getColor(context, R.color.key_hint_letter_color_lxx_light),
                    keyboardBackground = backgroundImage
                )
                THEME_DARK -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    ContextCompat.getColor(context, R.color.gesture_trail_color_lxx_dark),
                    "#263238".toColorInt(),
                    "#364248".toColorInt(),
                    "#2d393f".toColorInt(),
                    "#364248".toColorInt(),
                    ContextCompat.getColor(context, R.color.key_text_color_lxx_dark),
                    ContextCompat.getColor(context, R.color.key_hint_letter_color_lxx_dark),
                    keyboardBackground = backgroundImage
                )
                THEME_HOLO_WHITE -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.WHITE,
                    "#282828".toColorInt(),
                    Color.WHITE, // drawable is transparent
                    "#444444".toColorInt(), // should be 222222, but the key drawable is already grey
                    Color.WHITE,
                    Color.WHITE,
                    "#282828".toColorInt(),
                    Color.WHITE,
                    "#80FFFFFF".toColorInt(),
                    keyboardBackground = backgroundImage
                )
                THEME_DARKER -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    ContextCompat.getColor(context, R.color.gesture_trail_color_lxx_dark),
                    ContextCompat.getColor(context, R.color.keyboard_background_lxx_dark_border),
                    ContextCompat.getColor(context, R.color.key_background_normal_lxx_dark_border),
                    ContextCompat.getColor(context, R.color.key_background_functional_lxx_dark_border),
                    ContextCompat.getColor(context, R.color.key_background_normal_lxx_dark_border),
                    ContextCompat.getColor(context, R.color.key_text_color_lxx_dark),
                    ContextCompat.getColor(context, R.color.key_hint_letter_color_lxx_dark),
                    keyboardBackground = backgroundImage
                )
                THEME_BLACK -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    ContextCompat.getColor(context, R.color.gesture_trail_color_lxx_dark),
                    ContextCompat.getColor(context, R.color.background_amoled_black),
                    ContextCompat.getColor(context, R.color.background_amoled_dark),
                    ContextCompat.getColor(context, R.color.background_amoled_dark),
                    ContextCompat.getColor(context, R.color.background_amoled_dark),
                    ContextCompat.getColor(context, R.color.key_text_color_lxx_dark),
                    ContextCompat.getColor(context, R.color.key_hint_letter_color_lxx_dark),
                    keyboardBackground = backgroundImage
                )
                THEME_BLUE_GRAY -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(120, 144, 156),
                    Color.rgb(236, 239, 241),
                    Color.WHITE,
                    Color.rgb(207, 216, 220),
                    Color.WHITE,
                    Color.BLACK,
                    Color.BLACK,
                    keyboardBackground = backgroundImage
                )
                THEME_BROWN -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(141, 110, 99),
                    Color.rgb(239, 235, 233),
                    Color.WHITE,
                    Color.rgb(215, 204, 200),
                    Color.WHITE,
                    Color.BLACK,
                    Color.BLACK,
                    keyboardBackground = backgroundImage
                )
                THEME_CHOCOLATE -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(80, 128, 255),
                    Color.rgb(140, 112, 94),
                    Color.rgb(193, 163, 146),
                    Color.rgb(168, 127, 103),
                    Color.rgb(193, 163, 146),
                    Color.WHITE,
                    Color.WHITE,
                    keyboardBackground = backgroundImage
                )
                THEME_CLOUDY -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(255, 113, 129),
                    Color.rgb(81, 97, 113),
                    Color.rgb(117, 128, 142),
                    Color.rgb(99, 109, 121),
                    Color.rgb(117, 128, 142),
                    Color.WHITE,
                    Color.WHITE,
                    keyboardBackground = backgroundImage
                )
                THEME_FOREST -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(75, 110, 75),
                    Color.rgb(181, 125, 88),
                    Color.rgb(228, 212, 191),
                    Color.rgb(212, 186, 153),
                    Color.rgb(228, 212, 191),
                    Color.rgb(0, 50, 0),
                    Color.rgb(0, 50, 0),
                    Color.rgb(0, 50, 0),
                    Color.rgb(0, 80, 0),
                    keyboardBackground = backgroundImage
                )
                THEME_INDIGO -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(92, 107, 192),
                    Color.rgb(232, 234, 246),
                    Color.WHITE,
                    Color.rgb(197, 202, 233),
                    Color.WHITE,
                    Color.BLACK,
                    Color.BLACK,
                    keyboardBackground = backgroundImage
                )
                THEME_OCEAN -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(255, 124, 0),
                    Color.rgb(89, 109, 155),
                    Color.rgb(132, 157, 212),
                    Color.rgb(81, 116, 194),
                    Color.rgb(132, 157, 212),
                    Color.WHITE,
                    Color.WHITE,
                    keyboardBackground = backgroundImage
                )
                THEME_PINK -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(236, 64, 122),
                    Color.rgb(252, 228, 236),
                    Color.WHITE,
                    Color.rgb(248, 187, 208),
                    Color.WHITE,
                    Color.BLACK,
                    Color.BLACK,
                    keyboardBackground = backgroundImage
                )
                THEME_SAND -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(110, 155, 255),
                    Color.rgb(242, 232, 218),
                    Color.WHITE,
                    Color.rgb(234, 211, 185),
                    Color.WHITE,
                    Color.BLACK,
                    Color.BLACK,
                    keyboardBackground = backgroundImage
                )
                THEME_VIOLETTE -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(255, 96, 255),
                    Color.rgb(112, 112, 174),
                    Color.rgb(150, 150, 216),
                    Color.rgb(123, 123, 206),
                    Color.rgb(150, 150, 216),
                    Color.WHITE,
                    Color.WHITE,
                    keyboardBackground = backgroundImage
                )
                THEME_MIDNIGHT -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(108, 140, 255), // accent
                    Color.rgb(11, 16, 32),    // background
                    Color.rgb(21, 28, 46),    // keys
                    Color.rgb(30, 39, 64),    // functional keys
                    Color.rgb(21, 28, 46),    // spacebar
                    Color.rgb(230, 236, 255), // text
                    Color.rgb(169, 182, 217), // hint text
                    keyboardBackground = backgroundImage
                )
                THEME_NORD -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(136, 192, 208),
                    Color.rgb(46, 52, 64),
                    Color.rgb(59, 66, 82),
                    Color.rgb(67, 76, 94),
                    Color.rgb(59, 66, 82),
                    Color.rgb(236, 239, 244),
                    Color.rgb(216, 222, 233),
                    keyboardBackground = backgroundImage
                )
                THEME_GRUVBOX -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(254, 128, 25),
                    Color.rgb(40, 40, 40),
                    Color.rgb(60, 56, 54),
                    Color.rgb(80, 73, 69),
                    Color.rgb(60, 56, 54),
                    Color.rgb(235, 219, 178),
                    Color.rgb(213, 196, 161),
                    keyboardBackground = backgroundImage
                )
                THEME_SOLARIZED -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(38, 139, 210),
                    Color.rgb(0, 43, 54),
                    Color.rgb(7, 54, 66),
                    Color.rgb(11, 68, 82),
                    Color.rgb(7, 54, 66),
                    Color.rgb(147, 161, 161),
                    Color.rgb(131, 148, 150),
                    keyboardBackground = backgroundImage
                )
                THEME_TERMINAL -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(51, 255, 102),
                    Color.rgb(8, 18, 10),
                    Color.rgb(14, 31, 20),
                    Color.rgb(20, 48, 30),
                    Color.rgb(14, 31, 20),
                    Color.rgb(182, 255, 201),
                    Color.rgb(111, 227, 154),
                    keyboardBackground = backgroundImage
                )
                THEME_SUNSET -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(255, 138, 91),
                    Color.rgb(36, 26, 30),
                    Color.rgb(51, 36, 42),
                    Color.rgb(69, 47, 56),
                    Color.rgb(51, 36, 42),
                    Color.rgb(255, 233, 214),
                    Color.rgb(224, 187, 166),
                    keyboardBackground = backgroundImage
                )
                THEME_IOS27 -> DefaultColors(
                    themeStyle,
                    hasBorders,
                    Color.rgb(247, 206, 105),      // accent — swipe trail and selection highlights, not the action key
                    Ios27Palette.panel,            // panel
                    Ios27Palette.key,              // character keys
                    Ios27Palette.modifier,         // functional keys (shift/delete/globe)
                    Ios27Palette.key,              // spacebar, same family as character keys
                    Ios27Palette.CHARACTER,        // key letters
                    Ios27Palette.SECONDARY_ICON,   // corner hints, toolbar and spacebar icons
                    suggestionText = Ios27Palette.CHARACTER,
                    spaceBarText = Ios27Palette.SECONDARY_ICON,
                    keyboardBackground = backgroundImage,
                    // The design paints the panel and the keys as translucent material over the app
                    // behind it. Android IME windows can neither blur nor see through the app, so the
                    // glass is faked: the keys and the panel are genuinely translucent, and a user
                    // background image (pre-blurred by the user) is what shows through them. The
                    // alphas come from the palette above, so these scalars are 255 = "as written".
                    glassAlphas = GlassAlphas(
                        key = Ios27Palette.KEY_ALPHA,
                        keyPressed = Ios27Palette.KEY_ALPHA,
                        functionalKey = Ios27Palette.KEY_ALPHA,
                        functionalKeyPressed = Ios27Palette.KEY_ALPHA
                    ),
                    // The design's pressed key is a big step lighter and more opaque than the resting
                    // key, so the theme states the pressed colour itself instead of relying on the
                    // standard one-step brighten.
                    pressedKeyColor = Ios27Palette.pressed,
                    // HeliBoard paints the action key (enter / search / done) with the accent, which
                    // here is the gold of the swipe trail. The design wants a neutral key for it, so
                    // the action key gets its own grey and the gold stays on the trail.
                    actionKeyBackground = Ios27Palette.action,
                    // The design puts nothing in the key corners: no hint labels, no hint icons.
                    showKeyHints = false,
                    // panel surfaces: the translucent panel colour over the background image, with the
                    // design's soft shading on top as an untinted layer
                    panelOverImage = Ios27Palette.panel,
                    panelScrim = Ios27Palette.SCRIM_INTENSITY,
                    dividerColor = Ios27Palette.DIVIDER,
                    shading = Ios27Palette.shading,
                    // long-press popups and the more-suggestions popup use the same panel and key
                    // palette as the keyboard they opened from
                    popupKeysBackground = Ios27Palette.key,
                    popupPanelBackground = Ios27Palette.panel
                )
                else -> { // user-defined theme
                    val colorSettings = readUserColors(prefs, themeName)
                    val colors = readUserColorTheme(themeStyle, hasBorders, colorSettings, context, isNight, backgroundImage)
                    if (readUserMoreColors(prefs, themeName) == 2)
                        AllColors(readUserAllColors(prefs, themeName, colors), themeStyle, hasBorders, backgroundImage)
                    else {
                        colors
                    }
                }
            }
        }

        fun readUserColorTheme(themeStyle: String, hasBorders: Boolean, colorSettings: List<ColorSetting>, context: Context, isNight: Boolean, backgroundImage: Drawable?): Colors {
            return DefaultColors(
                themeStyle,
                hasBorders,
                determineUserColor(colorSettings, context, COLOR_ACCENT, isNight),
                determineUserColor(colorSettings, context, COLOR_BACKGROUND, isNight),
                determineUserColor(colorSettings, context, COLOR_KEYS, isNight),
                determineUserColor(colorSettings, context, COLOR_FUNCTIONAL_KEYS, isNight),
                determineUserColor(colorSettings, context, COLOR_SPACEBAR, isNight),
                determineUserColor(colorSettings, context, COLOR_TEXT, isNight),
                determineUserColor(colorSettings, context, COLOR_HINT_TEXT, isNight),
                determineUserColor(colorSettings, context, COLOR_SUGGESTION_TEXT, isNight),
                determineUserColor(colorSettings, context, COLOR_SPACEBAR_TEXT, isNight),
                determineUserColor(colorSettings, context, COLOR_GESTURE, isNight),
                backgroundImage,
            )
        }

        fun writeUserColors(prefs: SharedPreferences, themeName: String, colors: List<ColorSetting>) {
            val key = Settings.PREF_USER_COLORS_PREFIX + themeName
            val value = Json.encodeToString(colors.filter { it.color != null || it.auto == false })
            prefs.edit { putString(key, value) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }

        fun readUserColors(prefs: SharedPreferences, themeName: String): List<ColorSetting> {
            val key = Settings.PREF_USER_COLORS_PREFIX + themeName
            return Json.decodeFromString(prefs.getString(key, Defaults.PREF_USER_COLORS)!!)
        }

        fun writeUserMoreColors(prefs: SharedPreferences, themeName: String, value: Int) {
            val key = Settings.PREF_USER_MORE_COLORS_PREFIX + themeName
            prefs.edit { putInt(key, value) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }

        fun readUserMoreColors(prefs: SharedPreferences, themeName: String): Int {
            val key = Settings.PREF_USER_MORE_COLORS_PREFIX + themeName
            return prefs.getInt(key, Defaults.PREF_USER_MORE_COLORS)
        }

        fun writeUserAllColors(prefs: SharedPreferences, themeName: String, colorMap: EnumMap<ColorType, Int>) {
            val key = Settings.PREF_USER_ALL_COLORS_PREFIX + themeName
            prefs.edit { putString(key, colorMap.map { "${it.key},${it.value}" }.joinToString(";")) }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }

        fun readUserAllColors(prefs: SharedPreferences, themeName: String, fallback: Colors?): EnumMap<ColorType, Int> {
            val key = Settings.PREF_USER_ALL_COLORS_PREFIX + themeName
            val colorsString = prefs.getString(key, Defaults.PREF_USER_ALL_COLORS)!!
            val colorMap = EnumMap<ColorType, Int>(ColorType::class.java)
            colorsString.split(";").forEach {
                val ct = try {
                    ColorType.valueOf(it.substringBefore(",").uppercase())
                } catch (_: IllegalArgumentException) {
                    return@forEach
                }
                val i = it.substringAfter(",").toIntOrNull() ?: return@forEach
                colorMap[ct] = i
            }
            if (fallback != null && colorMap.size < ColorType.entries.size) {
                ColorType.entries.forEach {
                    if (it in colorMap) return@forEach
                    colorMap[it] = fallback.get(it)
                }
            }
            return colorMap
        }

        fun getUnusedThemeName(initialName: String, prefs: SharedPreferences): String {
            val existingNames = getExistingThemeNames(prefs)
            if (initialName !in existingNames) return initialName
            var i = 1
            while ("$initialName$i" in existingNames)
                i++
            return "$initialName$i"
        }

        private fun getExistingThemeNames(prefs: SharedPreferences) =
            prefs.all.keys.mapNotNull {
                when {
                    it.startsWith(Settings.PREF_USER_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_COLORS_PREFIX)
                    it.startsWith(Settings.PREF_USER_ALL_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_ALL_COLORS_PREFIX)
                    it.startsWith(Settings.PREF_USER_MORE_COLORS_PREFIX) -> it.substringAfter(Settings.PREF_USER_MORE_COLORS_PREFIX)
                    else -> null
                }
            }.toSortedSet()

        // returns false if not renamed due to invalid name or collision
        fun renameUserColors(from: String, to: String, prefs: SharedPreferences): Boolean {
            if (to.isBlank()) return false // don't want that
            if (to == from) return true // nothing to do
            val existingNames = getExistingThemeNames(prefs)
            if (to in existingNames) return false
            // all good, now rename
            prefs.edit {
                if (prefs.contains(Settings.PREF_USER_COLORS_PREFIX + from)) {
                    putString(Settings.PREF_USER_COLORS_PREFIX + to, prefs.getString(Settings.PREF_USER_COLORS_PREFIX + from, ""))
                    remove(Settings.PREF_USER_COLORS_PREFIX + from)
                }
                if (prefs.contains(Settings.PREF_USER_ALL_COLORS_PREFIX + from)) {
                    putString(Settings.PREF_USER_ALL_COLORS_PREFIX + to, prefs.getString(Settings.PREF_USER_ALL_COLORS_PREFIX + from, ""))
                    remove(Settings.PREF_USER_ALL_COLORS_PREFIX + from)
                }
                if (prefs.contains(Settings.PREF_USER_MORE_COLORS_PREFIX + from)) {
                    putInt(Settings.PREF_USER_MORE_COLORS_PREFIX + to, prefs.getInt(Settings.PREF_USER_MORE_COLORS_PREFIX + from, 0))
                    remove(Settings.PREF_USER_MORE_COLORS_PREFIX + from)
                }
                if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == from)
                    putString(Settings.PREF_THEME_COLORS, to)
                if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == from)
                    putString(Settings.PREF_THEME_COLORS_NIGHT, to)
            }
            return true
        }

        fun determineUserColor(colors: List<ColorSetting>, context: Context, colorName: String, isNight: Boolean): Int {
            val c = colors.firstOrNull { it.name == colorName }
            val color = c?.color
            val auto = c?.auto ?: true
            return if (auto || color == null)
                determineAutoColor(colors, colorName, isNight, context)
            else color
        }

        private fun determineAutoColor(colors: List<ColorSetting>, colorName: String, isNight: Boolean, context: Context): Int {
            when (colorName) {
                COLOR_ACCENT -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        // try determining accent color on Android 10 & 11, accent is not available in resources
                        val wrapper: Context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
                        val value = TypedValue()
                        if (wrapper.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) return value.data
                    }
                    return ContextCompat.getColor(Settings.getDayNightContext(context, isNight), R.color.accent)
                }
                COLOR_GESTURE -> return determineUserColor(colors, context, COLOR_ACCENT, isNight)
                COLOR_SUGGESTION_TEXT ->
                    return determineUserColor(colors, context, COLOR_TEXT, isNight)
                COLOR_TEXT -> {
                    // base it on background color, and not key, because it's also used for suggestions
                    val background = determineUserColor(colors, context, COLOR_BACKGROUND, isNight)
                    return if (isBrightColor(background)) {
                        // but if key borders are enabled, we still want reasonable contrast
                        if (!context.prefs().getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)
                            || isGoodContrast(Color.BLACK, determineUserColor(colors, context, COLOR_KEYS, isNight))
                        ) Color.BLACK
                        else Color.GRAY
                    } else Color.WHITE
                }
                COLOR_HINT_TEXT -> {
                    return if (isBrightColor(determineUserColor(colors, context, COLOR_KEYS, isNight))) Color.DKGRAY
                    else determineUserColor(colors, context, COLOR_TEXT, isNight)
                }
                COLOR_KEYS ->
                    return brightenOrDarken(determineUserColor(colors, context, COLOR_BACKGROUND, isNight), isNight)
                COLOR_FUNCTIONAL_KEYS ->
                    return brightenOrDarken(determineUserColor(colors, context, COLOR_KEYS, isNight), true)
                COLOR_SPACEBAR -> return determineUserColor(colors, context, COLOR_KEYS, isNight)
                COLOR_SPACEBAR_TEXT -> {
                    val spacebar = determineUserColor(colors, context, COLOR_SPACEBAR, isNight)
                    val hintText = determineUserColor(colors, context, COLOR_HINT_TEXT, isNight)
                    if (isGoodContrast(hintText, spacebar)) return hintText and -0x7f000001 // add some transparency
                    val text = determineUserColor(colors, context, COLOR_TEXT, isNight)
                    if (isGoodContrast(text, spacebar)) return text and -0x7f000001
                    return if (isBrightColor(spacebar)) Color.BLACK and -0x7f000001
                    else Color.WHITE and -0x7f000001
                }
                COLOR_BACKGROUND -> return ContextCompat.getColor(
                    Settings.getDayNightContext(context, isNight),
                    R.color.keyboard_background
                )
                else -> return ContextCompat.getColor(Settings.getDayNightContext(context, isNight), R.color.keyboard_background)
            }
        }
    }
}

@Serializable
data class ColorSetting(val name: String, val auto: Boolean?, val color: Int?) {
    var displayName = name
}
