# iOS 27 Dark theme (REA fork)

This fork carries a restyle ported from the maintainer's Figma design, supplied as a
React/CSS keyboard mock (`theme.tsx`). The design is a *keyboard* design; the note app and
status bar around it in the mock are the demo harness, not part of HeliBoard, so they are
not reproduced.

Everything the design specifies for the keyboard itself is implemented. This document
records the mapping and, importantly, the things the platform will not do, so a later
tweak does not have to rediscover them.

## Where it lives

| Piece | File |
| --- | --- |
| Palette | `keyboard/KeyboardTheme.kt` — `THEME_IOS27` (`rea_ios27`) |
| Panel + key geometry | `res/drawable/keyboard_background_lxx_base_border.xml`, `btn_keyboard_key_*_lxx_base_border.xml` |
| Suggestion strip | `res/drawable/keyboard_suggest_strip_lxx_base_border.xml` |
| Popup keys / panel | `res/drawable/btn_keyboard_key_popup_lxx_base.xml`, `keyboard_popup_panel_background_lxx_base.xml` |
| It ships as the default | `latin/settings/Defaults.kt` |

The palette is offered as **iOS 27 Dark** in *Appearance → Colors* and is the shipped
default for both day and night. Key borders are on by default because the iOS look depends
on keys having a visible rounded shape, which is the bordered drawable path — with borders
off HeliBoard paints keys through a different, flat drawable.

## Palette

The design's layers are translucent (`#696b75aa`, `#202127cc`, …) because they composite
over the app behind them. An IME cannot blur or sample what is behind it on Android, so
the palette stores the colours **already composited** onto the design's own panel. That is
the colour you actually see in the Figma mock.

| Design token | Design value | Theme field | Value used |
| --- | --- | --- | --- |
| accent, cursor, swipe trail | `#f7ce69` | accent, gesture | `#f7ce69` |
| keyboard panel ramp | `#45495788` → `#202127cc` → `#27282ee6` | background | `#2c2f38` |
| key ramp | `#696b75aa` → `#4b4d57bb` | keys, spacebar | `#56585f` |
| functional key ramp | `#30333dcc` → `#282a32dd` | functional keys | `#2f323c` |
| `:root` foreground | `#f5f5f7` | key text, suggestion text | `#f5f5f7` |
| footer icon / hint | `#b4b5bf` | hint text | `#b4b5bf` |
| trackpad hint | `#c5c7d0` | spacebar text | `#c5c7d0` |

## How the gradients work

`Colors` paints every key by tinting a drawable with a single colour using
`PorterDuff.Mode.MULTIPLY` (see `setColor` in `latin/common/Colors.kt`). A normal
two-tone gradient would therefore be flattened to one flat colour.

It survives anyway, because MULTIPLY *multiplies* rather than replaces: a **greyscale
ramp** baked into the drawable is scaled by the tint. So each key drawable carries a
white-to-grey ramp, and the theme colour becomes the light end of the rendered gradient:

```
drawable   #FFFFFF  ──────────────►  #CCCCCC
tint       #56585F  ×              ×  #56585F
rendered   #56585F  ──────────────►  #45474D      <- the design's two stops
```

The panel is the same trick with three stops: white (1.0), `#A8A8A8` (0.66) at 45% height,
then `#DEDEDE` (0.87). Tinting with `#2c2f38` reproduces the design's
`#454957 → #202127 → #27282e` ramp.

This is why the drawables hold greyscale values and not the design's colours: **changing a
theme's colours must not require editing the drawables, and editing a drawable changes
every theme.** If you want to retune the ramp, edit the greyscale stops; if you want to
retune the hue, edit the palette in `KeyboardTheme.kt`.

## Approximated or not reproducible

Stated plainly, because these are the places where the build will not look exactly like
Figma:

1. **No backdrop blur.** `backdrop-filter: blur(40px) saturate(140%)` cannot be done by an
   IME. The panel is rendered opaque instead of frosted. This is the single biggest
   visual difference. Android 12+ does have cross-window blur
   (`WindowManager.LayoutParams.setBackgroundBlurRadius`), which could approximate it on
   supporting devices, but it is device-dependent and was not attempted.
2. **No inset top highlight on keys.** `inset 0 1px 0 #ffffff25` needs a highlight
   *lighter* than the key colour. MULTIPLY can only darken, so the highlight would render
   as the key colour and vanish. Omitted rather than faked.
3. **Pressed keys brighten only slightly.** The design's pressed key (`#888b99`) is much
   lighter than the resting key. HeliBoard computes the pressed colour in code by
   brightening the resting colour by a fixed small step, so the press feedback is subtler
   than the mock. Matching it exactly means changing that computation, which would affect
   every theme.
4. **Gradient angle.** CSS `160deg`/`150deg` are near-vertical; Android gradients only take
   45° steps, so both are rendered as `270` (straight top to bottom). Very close, not
   identical.
5. **The ramp restarts at the suggestion strip.** The strip is a separate view with its own
   background, so it restarts the ramp rather than continuing it. The strip sits at the
   light end of the ramp, so this reads as a lit top edge rather than a seam.
6. **Key icons/labels are HeliBoard's.** The design's shift/delete/globe/mic glyphs are
   inline SVGs that do not exist as Android drawables here; the layouts and spacing are
   HeliBoard's, not iOS's.
7. **The settings UI is untouched.** The design covers the keyboard only. The Material 3
   settings screens keep HeliBoard's theming, which on Android 12+ follows the system
   accent (see `res/values-v31/colors.xml`). To force the design's gold accent app-wide,
   override `highlight_color_lxx_dark` in `values/colors.xml` and
   `values-v31/colors.xml`.

## Reverting to stock appearance

In `latin/settings/Defaults.kt`:

```kotlin
const val PREF_THEME_COLORS = KeyboardTheme.THEME_IOS27   → THEME_LIGHT
const val PREF_THEME_COLORS_NIGHT = KeyboardTheme.THEME_IOS27 → THEME_DARK
const val PREF_THEME_KEY_BORDERS = true                    → false
```

The drawable restyle is not behind a flag: it applies to the bordered look of the Material
and Rounded styles for every theme. Those drawables are referenced only by
`themes-lxx-base-border.xml` and `themes-rounded-base-border.xml`, so nothing else in the
app reads them.
