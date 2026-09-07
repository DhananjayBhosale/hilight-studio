# Highlight Studio design system

Highlight Studio is the Google Play distribution name. The GitHub flavor retains the HiLight Studio name;
both distributions share the same codebase and visual system while using separate package identities.

## Direction

- Pixel-native, candid, and experimental.
- Explain privileged access, privacy consequences, safety limits, and live state where they matter.
- Keep Material controls familiar; hardware effects provide the colour and personality.

## Tokens

- Use Material You dynamic colour first.
- Fallback light background `#FEF7FF`, surface `#F2ECF4`, primary `#5B3FBF`.
- Fallback dark background `#121116`, surface `#1E1D22`, primary `#B69DFF`.
- Roboto/system Material 3 typography, 14sp body and 16sp medium-weight title.
- Spacing: 6dp, 10dp, 16dp, 18dp. Rounding: 10dp, 16dp, 22dp, 28dp, 36dp.

## Components

- Reuse `PixelCard`, `SectionTitle`, `Caption`, Material buttons, and existing dialogs.
- Permission disclosures use the standard extra-large `AlertDialog`, direct copy, one continuation
  action, and a clear `Not now` exit.
- Never rely on colour alone for access or renderer status.

## Motion and accessibility

- Motion communicates state in 150 to 250ms; no decorative choreography.
- Preserve Material touch targets, system theme, dynamic colour, and English/Japanese parity.

## Public website

- Treat the website as a dark hardware-instrument surface, not a generic app landing page.
- Use `oklch(16% 0.018 285)` for the canvas, `oklch(94% 0.012 285)` for primary text,
  and `oklch(76% 0.16 285)` for focus and primary actions.
- The eight-LED sequence is the reusable brand signature. It may animate once, but all meaning must
  remain available in text and motion must stop under `prefers-reduced-motion`.
- Use asymmetric layouts, full-width bands, numbered steps, and verified product screenshots instead
  of repeated marketing cards.
- Website copy leads with supported hardware, experimental status, privacy, and setup requirements.
