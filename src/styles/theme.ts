// Nord Color Palette
// https://www.nordtheme.com/docs/colors-and-palettes

// Polar Night (backgrounds)
const nord0 = '#2E3440';  // darkest - main background
const nord1 = '#3B4252';  // dark - secondary background
const nord2 = '#434C5E';  // medium - elevated surfaces
const nord3 = '#4C566A';  // light - borders, dividers

// Snow Storm (foregrounds)
const nord4 = '#D8DEE9';  // light - secondary text
const nord5 = '#E5E9F0';  // lighter - primary text
const nord6 = '#ECEFF4';  // lightest - highlighted text

// Frost (accent blues/cyans)
const nord7 = '#8FBCBB';  // cyan - muted accent
const nord8 = '#88C0D0';  // bright cyan - primary accent
const nord9 = '#81A1C1';  // blue - links, info
const nord10 = '#5E81AC'; // dark blue - secondary accent

// Aurora (status colors)
const nord11 = '#BF616A'; // red - errors
const nord12 = '#D08770'; // orange - warnings
const nord13 = '#EBCB8B'; // yellow - warnings alt
const nord14 = '#A3BE8C'; // green - success
const nord15 = '#B48EAD'; // purple - special highlights

export const colors = {
  // Backgrounds
  background: nord0,
  backgroundSecondary: nord1,
  backgroundTertiary: nord2,

  // Text
  textPrimary: nord5,
  textSecondary: nord4,
  textTertiary: nord3,

  // Accents
  accent: nord8,        // bright cyan - primary interactive elements
  accentDim: nord7,     // muted cyan - secondary interactive elements
  accentSecondary: nord9, // blue - alternative accent

  // Borders & Dividers
  border: nord3,
  borderLight: nord2,

  // Status Colors
  error: nord11,
  warning: nord13,
  success: nord14,
  info: nord9,

  // Special
  highlight: nord15,    // purple for special items
  link: nord9,          // blue for links
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
};

export const typography = {
  title: {
    fontSize: 32,
    fontWeight: 'bold' as const,
    color: colors.textPrimary,
  },
  subtitle: {
    fontSize: 18,
    fontWeight: '600' as const,
    color: colors.textPrimary,
  },
  body: {
    fontSize: 16,
    color: colors.textPrimary,
  },
  caption: {
    fontSize: 14,
    color: colors.textSecondary,
  },
  mono: {
    fontSize: 14,
    fontFamily: 'monospace',
    color: colors.accent,
  },
};
