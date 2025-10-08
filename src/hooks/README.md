# Custom Hooks

## `useStyles` - Theme-Aware Styling

A hook that simplifies creating theme-aware styles with automatic memoization.

### Basic Usage

```typescript
import { useStyles } from '../hooks/useStyles';
import type { Theme } from '../hooks/useStyles';

function MyScreen() {
  const styles = useStyles(createStyles);

  return <View style={styles.container}>...</View>;
}

const createStyles = (theme: Theme) => ({
  container: {
    backgroundColor: theme.colors.background,
    padding: theme.spacing.lg,
  },
  title: {
    ...theme.typography.title,
    marginBottom: theme.spacing.md,
  },
});
```

### Theme Object

The `Theme` object passed to your style function contains:

```typescript
{
  colors: ColorScheme,    // All Nord colors (background, text, accent, etc.)
  spacing: {              // Spacing scale
    xs: 4,
    sm: 8,
    md: 16,
    lg: 24,
    xl: 32,
  },
  typography: {           // Text styles
    title: TextStyle,
    subtitle: TextStyle,
    body: TextStyle,
    caption: TextStyle,
    mono: TextStyle,
  },
  isDark: boolean,        // True if in dark mode
}
```

### Benefits

1. **Automatic Memoization**: Styles are memoized and only recreated when the theme changes
2. **Type Safety**: Full TypeScript support with autocomplete
3. **Clean Code**: No manual `useMemo` or dependency arrays
4. **Theme-Aware**: Automatically responds to OS light/dark mode changes
5. **Consistent**: Single pattern across all screens

### Accessing Theme Values Outside Styles

If you need theme values outside of styles (e.g., for component props):

```typescript
import { useStyles } from '../hooks/useStyles';
import { useTheme } from '../styles/theme';

function MyScreen() {
  const styles = useStyles(createStyles);
  const { colors } = useTheme();

  return (
    <Switch
      trackColor={{ false: colors.border, true: colors.accentDim }}
      thumbColor={value ? colors.accent : colors.textSecondary}
    />
  );
}
```

### Pattern Comparison

**❌ Old Pattern (Verbose)**
```typescript
function MyScreen() {
  const { colors, spacing, typography } = useTheme();

  const styles = React.useMemo(() => StyleSheet.create({
    container: {
      backgroundColor: colors.background,
      padding: spacing.lg,
    },
  }), [colors, spacing, typography]);

  return <View style={styles.container}>...</View>;
}
```

**✅ New Pattern (Clean)**
```typescript
function MyScreen() {
  const styles = useStyles(createStyles);
  return <View style={styles.container}>...</View>;
}

const createStyles = (theme: Theme) => ({
  container: {
    backgroundColor: theme.colors.background,
    padding: theme.spacing.lg,
  },
});
```

### Migration Guide

1. Replace `import { colors, spacing, typography } from '../styles/theme'` with:
   ```typescript
   import { useStyles } from '../hooks/useStyles';
   import type { Theme } from '../hooks/useStyles';
   ```

2. Move your `StyleSheet.create()` to a separate function at the bottom:
   ```typescript
   const createStyles = (theme: Theme) => ({
     // your styles here, using theme.colors, theme.spacing, etc.
   });
   ```

3. Use the hook in your component:
   ```typescript
   const styles = useStyles(createStyles);
   ```

4. If you need theme values outside styles, add:
   ```typescript
   import { useTheme } from '../styles/theme';
   const { colors } = useTheme();
   ```
