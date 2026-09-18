# HiLight Studio Design System

## Brand Direction

- Pixel-native, candid, experimental.
- Trust comes from precise live state, plain safety copy, and familiar Material behavior.
- Avoid gaming-RGB decoration, repeated cards, hidden constraints, and unfamiliar controls.

## Color Tokens

- Prefer Material You dynamic colour from `Theme.kt`.
- Fallback primary: `#B69DFF` dark, `#5B3FBF` light.
- Fallback secondary: `#7FD8E8` dark.
- Background: `#121116` dark, `#FEF7FF` light.
- Use semantic Material error/warning/success roles; never rely on colour without text.

## Typography

- Android system Material 3 typography.
- Titles use medium weight; body explanations use `bodySmall` or `bodyMedium`.
- No display fonts, all-caps labels, or decorative type.

## Spacing / Shape

- Horizontal screen rhythm: 16dp. Card content: 18dp. Related controls: 10dp.
- Shapes: 10/16/22/28/36dp from extra-small through extra-large.
- Tonal layering replaces decorative shadows.

## Components

- Reuse `PixelCard`, `SectionTitle`, `Caption`, `PixelSlider`, `PixelToggleRow`, and Material buttons.
- Use standard app pickers and segmented controls for scope/activity selection.
- Safety extensions reuse `GatedDurationSlider` and its two-step warning.
- Live status always pairs colour/dot with a text label.
- Root is detected automatically; Shizuku and ADB appear only as fallbacks when root is unavailable
  or denied.

## Motion

- Motion communicates state only.
- Preserve existing spring press feedback and short 150–250ms visibility transitions.
- Respect reduced-motion/system behavior where available.
