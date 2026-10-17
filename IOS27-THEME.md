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
| Suggestion strip | `res/drawable/keyboard_suggest_strip_lxx_base_border.xml`, `res/drawable/suggestions_strip_divider.xml` |
| Borderless spacebar | `res/drawable/btn_keyboard_spacebar_base.xml` **and** `res/drawable-v26/btn_keyboard_spacebar_base.xml` — the `-v26` copy wins on every modern device, so keep the two in step |
| Popup keys / panel | `res/drawable/btn_keyboard_key_popup_lxx_base.xml`, `keyboard_popup_panel_background_lxx_base.xml` |
| It ships as the default | `latin/settings/Defaults.kt` |

The palette is offered as **iOS 27 Dark** in *Appearance → Colors* and is the shipped
default for both day and night. Key borders are on by default because the iOS look depends
on keys having a visible rounded shape, which is the bordered drawable path — with borders
off HeliBoard paints keys through a different, flat drawable.

## Palette

The design's layers are translucent (`#696b75aa`, `#202127cc`, …) because they composite
over the app behind them. The palette stores the colours **already composited** onto the
design's own panel, i.e. the hue you see in the Figma mock; the design's translucency is
put back by the glass layer described below.

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

The suggestion strip uses the same trick with three stops: white (1.0), `#A8A8A8` (0.66) at
45% height, then `#DEDEDE` (0.87). Tinting with `#2c2f38` reproduces the design's
`#454957 → #202127 → #27282e` ramp.

That ramp only ever shows on the suggestion strip. The panel *behind the keys* is not
drawn from `keyboard_background_lxx_base_border.xml` at all: `KeyboardView.setKeyboard`
replaces the main keyboard view's XML background with `Color.TRANSPARENT` ("actual
background color/drawable is applied to main_keyboard_frame"), and `InputView` gives
`main_keyboard_frame` a white background carrying the `background` colour as a `MODULATE`
filter. So the panel behind the keys is one flat `#2c2f38`, and only the strip carries a
ramp. The `keyboard_background_lxx_base_border.xml` restyle still matters for the emoji
*search* keyboard, which does draw that drawable.

This is why the drawables hold greyscale values and not the design's colours: **changing a
theme's colours must not require editing the drawables, and editing a drawable changes
every theme.** If you want to retune the ramp, edit the greyscale stops; if you want to
retune the hue, edit the palette in `KeyboardTheme.kt`.

## Glass: real translucency for this theme only

The design composites the keyboard over the app behind it. `PorterDuff.Mode.MULTIPLY`
cannot express that, no matter what alpha the tint carries: it composites alpha as
`Sa + Da - Sa*Da`, so an opaque drawable stays opaque. Real translucency therefore needs a
different compositing mode, and it is opt-in per theme:

- `GlassAlphas` (in `latin/common/Colors.kt`) is a per-surface alpha bag passed to
  `DefaultColors`. **Only `THEME_IOS27` passes one**; every other theme — and every
  user-defined theme — leaves it `null` and takes the untouched `MULTIPLY` path, so they
  render bit-for-bit as before.
- Keys / functional keys / spacebar / action keys are tinted with `PorterDuff.Mode.SRC_IN`
  instead. `SRC_IN` composites as `Sa * Da`, so the tint's alpha becomes real alpha, and
  the drawable's own alpha channel masks it — which is what keeps the rounded corners and
  the transparent "empty key" state.
- The panel (the keyboard background behind the keys) is tinted through the existing
  `MODULATE` colour filter, which already honours alpha, so giving the panel colour an
  alpha is enough to make the panel translucent.
- Alphas are **scaled, not overwritten**, for the state lists. Several surfaces carry a
  deliberate alpha already (e.g. the suggestion strip's `0x22ffffff` press flash); those
  must stay faint.

Alphas used:

| Surface | Alpha |
| --- | --- |
| character keys | `0xAA` (design's own `#696b75aa`) |
| character keys, pressed | `0xBB` (design's own `#4b4d57bb`) |
| functional keys / pressed | `0xB4` / `0xC6` |
| panel, suggestion strip | `0xC8` |

The panel keeps a higher alpha than the keys because it is the only near-opaque base in the
one case where there is nothing else behind it — see below.

### What you get, and what you give up

With a **keyboard background image** set (HeliBoard's own feature, *Appearance → Keyboard
background image*) the effect is the one the design wants: the image is the panel, the keys
and strip are genuinely translucent panes over it, so the image shows through the keys and
at full strength in the gaps between them. The text stays fully opaque, because the
translucency lives in the key *background* drawable, not in the view.

Trade-offs, stated plainly:

1. **The baked colour ramps stop being visible on glassed surfaces.** `SRC_IN` takes only
   the tint's colour, so a surface becomes one flat translucent colour per state instead of
   a light-to-dark ramp. The design's key ramp is subtle and its *alpha* ramp is kept (via
   the pressed state), but the RGB ramp on keys and on the suggestion strip is gone. This
   is unavoidable without giving this one theme its own copies of the key drawables and
   selecting them in code — the drawables are chosen by the keyboard *style*, which is
   shared with every other theme, so an alpha-ramped drawable there would flatten every
   other theme's keys instead.
2. **Panel without a background image.** With no image, the panel composite is over the IME
   window's own background, which is an opaque platform colour the app does not control
   (`<application>` declares no theme). So the panel is genuinely translucent, but what
   shows through is that window colour, not the app. The high `0xC8` alpha keeps that safe
   on a light window background. With an image set this does not apply at all.
3. **The panel is the image.** When a background image is set, `Colors` puts the image
   itself behind the keys. The panel is therefore not a translucent scrim *on top of* the
   image — the image is not dimmed. If you want a frosted slab over the image instead (the
   image visible but dimmed), that is a small change to the `MAIN_BACKGROUND` branch.
4. **Only the keyboard view.** The emoji and clipboard panels declare
   `android:background="@android:color/transparent"` in their layouts, so they were never
   coloured by the panel path and are unaffected either way.
5. **API floor.** `SRC_IN` composites the same way from API 21 up. The panel keeps using the
   app's existing `MODULATE` helper (`BlendModeColorFilterCompat`), which honours alpha on
   every supported API, so the panel alpha behaves the same on old and new devices.

To go back to fully opaque rendering, delete the `glassAlphas = GlassAlphas(…)` argument
from the `THEME_IOS27` branch in `KeyboardTheme.kt`. Nothing else needs to change.

## Approximated or not reproducible

Stated plainly, because these are the places where the build will not look exactly like
Figma:

1. **No backdrop blur.** `backdrop-filter: blur(40px) saturate(140%)` cannot be done by an
   IME. The keyboard surfaces are genuinely translucent instead (see *Glass* above), so
   what is behind them shows through, but it is not blurred. Android 12+ does have
   cross-window blur (`WindowManager.LayoutParams.setBackgroundBlurRadius`), which could
   approximate it on supporting devices, but it is device-dependent and was not attempted.
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
