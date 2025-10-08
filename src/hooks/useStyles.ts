import { useMemo } from 'react';
import { StyleSheet, ImageStyle, TextStyle, ViewStyle } from 'react-native';
import { useTheme } from '../styles/theme';
import type { ColorScheme } from '../styles/theme';

export type Theme = {
  colors: ColorScheme;
  spacing: {
    xs: number;
    sm: number;
    md: number;
    lg: number;
    xl: number;
  };
  typography: {
    title: TextStyle;
    subtitle: TextStyle;
    body: TextStyle;
    caption: TextStyle;
    mono: TextStyle;
  };
  isDark: boolean;
};

type NamedStyles<T> = {
  [P in keyof T]: ViewStyle | TextStyle | ImageStyle;
};

/**
 * Hook to create theme-aware styles with automatic memoization
 *
 * @example
 * function MyScreen() {
 *   const styles = useStyles(createStyles);
 *   return <View style={styles.container}>...</View>;
 * }
 *
 * const createStyles = (theme: Theme) => ({
 *   container: {
 *     backgroundColor: theme.colors.background,
 *     padding: theme.spacing.lg,
 *   },
 *   title: {
 *     ...theme.typography.title,
 *     marginBottom: theme.spacing.md,
 *   },
 * });
 */
export function useStyles<T extends NamedStyles<T>>(
  stylesFn: (theme: Theme) => T
): T {
  const theme = useTheme();

  return useMemo(
    () => StyleSheet.create(stylesFn(theme)),
    [theme, stylesFn]
  );
}
