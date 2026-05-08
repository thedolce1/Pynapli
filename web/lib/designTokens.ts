/**
 * Mirrors DESIGN_TOKENS.md. Components consume tokens.
 * Feature pages must not hardcode visual values.
 */

export const colors = {
  black: "#050505",
  surface: "#101010",
  surfaceRaised: "#1A1A1A",
  surfacePressed: "#242424",

  textPrimary: "#F5F5F5",
  textSecondary: "#B8B8B8",
  textTertiary: "#7A7A7A",
  textInverse: "#080808",

  accent: "#FFC928",
  accentDark: "#B88700",
  success: "#18D77A",
  danger: "#FF4D5E",
  warning: "#FFA23A",
  info: "#4DA3FF",

  border: "#2A2A2A",
  overlayScrim: "rgba(0,0,0,0.6)",
  photoGradientBottom: "rgba(0,0,0,0.8)",
} as const;

export const spacing = {
  xxs: "2px",
  xs: "4px",
  sm: "8px",
  md: "16px",
  lg: "24px",
  xl: "32px",
  xxl: "48px",
} as const;

export const radius = {
  none: "0px",
  small: "4px",
  card: "8px",
  sheet: "16px",
  pill: "9999px",
} as const;

export const type = {
  display: "32px",
  title: "22px",
  section: "18px",
  body: "16px",
  bodySmall: "14px",
  label: "13px",
  caption: "12px",
  micro: "11px",
} as const;

export const motion = {
  fast: 120,
  standard: 220,
  slow: 360,
} as const;
