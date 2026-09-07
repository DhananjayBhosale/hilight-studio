# Highlight Studio Web Design

## Direction

A dark, instrument-like surface for a Pixel enthusiast checking an experimental LED controller at a desk in evening light. The interface is quiet around the content, while the eight-LED spectrum supplies controlled energy.

## Color

- Strategy: committed dark neutral with an eight-color LED sequence used for identity and state.
- Canvas: `oklch(16% 0.018 285)`.
- Raised surface: `oklch(21% 0.022 285)`.
- Primary text: `oklch(94% 0.012 285)`.
- Secondary text: `oklch(73% 0.025 285)`.
- Focus and primary action: `oklch(76% 0.16 285)`.
- Success: `oklch(78% 0.16 155)`.
- Warning: `oklch(82% 0.16 80)`.

## Typography

- Use Geologica when available, with a compact system sans fallback.
- Display headings use strong weight and tight tracking.
- Body copy stays between 60 and 72 characters per line.
- Labels use sentence case, never all-caps paragraphs.

## Layout

- Desktop uses a 12-column asymmetric grid with the product story on the left and verified app imagery on the right.
- Mobile collapses into one deliberate reading order.
- Section spacing varies between tight operational clusters and generous narrative breaks.
- Avoid repetitive card grids. Use dividers, numbered steps, and full-width bands.

## Components

- Persistent header with four routes: Overview, Privacy, Support, Testing.
- Eight-segment LED signature used as a decorative, non-semantic brand mark.
- Primary button uses a solid violet fill; secondary actions use a restrained full border.
- Compatibility notice uses a full tinted panel and an icon plus text, never color alone.
- Footer states independence from Google and links to legal/support routes.

## Motion

- A single restrained LED activation sequence on first load.
- Hover and focus transitions last 160 to 220ms with ease-out-quart.
- Disable non-essential motion under `prefers-reduced-motion`.
