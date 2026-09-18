---
name: HiLight Studio
description: Pixel-native controls for the Pixel 11 HiLight LED array
colors:
  fallback-primary-dark: "#B69DFF"
  fallback-primary-light: "#5B3FBF"
  fallback-secondary-dark: "#7FD8E8"
  fallback-background-dark: "#121116"
  fallback-background-light: "#FEF7FF"
  fallback-surface-dark: "#1E1D22"
  fallback-surface-light: "#F2ECF4"
typography:
  body:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "21sp"
  title:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "16sp"
    fontWeight: 500
rounded:
  xs: "10dp"
  sm: "16dp"
  md: "22dp"
  lg: "28dp"
  xl: "36dp"
spacing:
  xs: "6dp"
  sm: "10dp"
  md: "16dp"
  lg: "18dp"
components:
  tonal-card:
    backgroundColor: "{colors.fallback-surface-dark}"
    rounded: "{rounded.lg}"
    padding: "18dp"
---

# Design System: HiLight Studio

## Overview

**Creative North Star: "Pixel Hardware Lab"**

The interface should feel like a careful extension of Pixel Settings: familiar controls, rounded
tonal surfaces, restrained colour, and immediate state feedback. Dynamic Material colour is the
primary palette; the values above are fallbacks. Experimental hardware access should feel candid and
controlled, never like a gaming dashboard or a decorative AI-generated concept.

## Colors

Wallpaper-derived Material You colour is preferred. The fallback palette uses violet for primary
actions, cyan for healthy secondary state, and softly tinted near-black or near-white surfaces.
Accent colour communicates action or status, not decoration; effect colours stay inside previews.

## Typography

Use the Android system Material 3 typography stack. Headlines and titles use medium weight with
slightly tighter tracking; body copy stays readable and compact. Avoid display fonts and uppercase
labels. Keep explanations short enough to scan in Settings.

## Elevation

Depth comes from Material tonal surface steps rather than prominent shadows. Cards are flat at rest;
press feedback uses a small spring scale and ripple.

## Components

- **Cards:** 28dp rounded tonal containers, 18dp internal padding, one semantic group per card.
- **Buttons:** standard Material filled, tonal, and text buttons with familiar hierarchy.
- **Selectors:** Material segmented buttons for small exclusive choices; app pickers for package scope.
- **Sliders:** value shown in a secondary-container pill; dangerous extensions use the established
  two-step confirmation.
- **Status:** a labelled pill plus dot; colour is accompanied by text.
- **Motion:** short state transitions and press feedback only. No decorative page choreography.

## Do's and Don'ts

- Do reuse `PixelCard`, `SectionTitle`, `Caption`, `PixelSlider`, and existing effect controls.
- Do keep root detection automatic and show alternative setup methods only when root is unavailable
  or denied.
- Do ship English and Japanese strings together.
- Don't nest cards, invent permission terminology, or expose package names observed from AppOps.
- Don't let cooldown fall through to ambient output; released hardware is a meaningful state.
