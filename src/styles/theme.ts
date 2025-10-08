// Nord Color Palette
// https://www.nordtheme.com/docs/colors-and-palettes

// Polar Night (dark backgrounds)
const nord0 = '#2E3440';
const nord1 = '#3B4252';
const nord2 = '#434C5E';
const nord3 = '#4C566A';

// Snow Storm (light backgrounds/dark text)
const nord4 = '#D8DEE9';
const nord5 = '#E5E9F0';
const nord6 = '#ECEFF4';

// Frost (accent blues/cyans)
const nord7 = '#8FBCBB';
const nord8 = '#88C0D0';
const nord9 = '#81A1C1';
const nord10 = '#5E81AC';

// Aurora (status colors)
const nord11 = '#BF616A';
const nord12 = '#D08770';
const nord13 = '#EBCB8B';
const nord14 = '#A3BE8C';
const nord15 = '#B48EAD';

export type ColorScheme = {
  // Backgrounds
  background: string;
  backgroundSecondary: string;
  backgroundTertiary: string;

  // Text
  textPrimary: string;
  textSecondary: string;
  textTertiary: string;

  // Accents
  accent: string;
  accentDim: string;
  accentSecondary: string;

  // Borders & Dividers
  border: string;
  borderLight: string;

  // Status Colors
  error: string;
  warning: string;
  success: string;
  info: string;

  // Special
  highlight: string;
  link: string;
};

// Dark Mode (Polar Night backgrounds, Snow Storm text)
export const darkColors: ColorScheme = {
  background: nord0,
  backgroundSecondary: nord1,
  backgroundTertiary: nord2,

  textPrimary: nord5,
  textSecondary: nord4,
  textTertiary: nord3,

  accent: nord8,
  accentDim: nord7,
  accentSecondary: nord9,

  border: nord3,
  borderLight: nord2,

  error: nord11,
  warning: nord13,
  success: nord14,
  info: nord9,

  highlight: nord15,
  link: nord9,
};

// Light Mode (Snow Storm backgrounds, Polar Night text)
export const lightColors: ColorScheme = {
  background: nord6,
  backgroundSecondary: nord5,
  backgroundTertiary: nord4,

  textPrimary: nord0,
  textSecondary: nord2,
  textTertiary: nord3,

  accent: nord10,        // darker blue for better contrast on light
  accentDim: nord9,
  accentSecondary: nord8,

  border: nord4,
  borderLight: nord5,

  error: nord11,
  warning: nord12,       // orange instead of yellow for better contrast
  success: nord14,
  info: nord10,

  highlight: nord15,
  link: nord10,
};

// No default export - use useTheme() hook instead
// This enforces dynamic theming and prevents static color usage

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
};

// Typography factory - takes colors as parameter
export const createTypography = (colors: ColorScheme) => ({
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
});

// No default typography export - use useTheme() hook instead

// Hook to get theme based on OS color scheme
import { useColorScheme } from 'react-native';

export function useTheme() {
  const colorScheme = useColorScheme();
  const isDark = colorScheme === 'dark';
  const colors = isDark ? darkColors : lightColors;
  const typography = createTypography(colors);

  return {
    colors,
    spacing,
    typography,
    isDark,
  };
}
