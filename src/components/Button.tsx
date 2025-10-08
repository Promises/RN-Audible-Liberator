import React from 'react';
import { TouchableOpacity, Text, StyleSheet, ActivityIndicator, ViewStyle, TextStyle } from 'react-native';
import { useTheme, ColorScheme } from '../styles/theme';

type ButtonVariant = 'filled' | 'outlined';
type ButtonState = 'primary' | 'neutral' | 'error' | 'warning' | 'success' | 'info';

interface ButtonProps {
  title: string;
  onPress: () => void;
  variant?: ButtonVariant;
  state?: ButtonState;
  disabled?: boolean;
  loading?: boolean;
  style?: ViewStyle;
}

export default function Button({
  title,
  onPress,
  variant = 'filled',
  state = 'neutral',
  disabled = false,
  loading = false,
  style,
}: ButtonProps) {
  const { colors, spacing } = useTheme();

  const buttonStyles = [
    styles.button,
    {
      paddingVertical: spacing.md,
      paddingHorizontal: spacing.lg,
    },
    getStateStyle(variant, state, disabled, colors),
    style,
  ];

  const textStyles = [
    styles.text,
    getTextStyle(variant, state, disabled, colors),
  ];

  return (
    <TouchableOpacity
      style={buttonStyles}
      onPress={onPress}
      disabled={disabled || loading}
      activeOpacity={0.7}
    >
      {loading ? (
        <ActivityIndicator
          size="small"
          color={getLoaderColor(variant, state, colors)}
        />
      ) : (
        <Text style={textStyles}>{title}</Text>
      )}
    </TouchableOpacity>
  );
}

// Get background and border colors based on variant and state
function getStateStyle(
  variant: ButtonVariant,
  state: ButtonState,
  disabled: boolean,
  colors: ColorScheme
): ViewStyle {
  if (disabled) {
    return {
      backgroundColor: variant === 'filled' ? colors.backgroundTertiary : 'transparent',
      borderColor: colors.border,
      opacity: 0.5,
    };
  }

  const stateColors: Record<ButtonState, { bg: string; border: string }> = {
    primary: { bg: colors.accent, border: colors.accent },
    neutral: { bg: colors.backgroundSecondary, border: colors.border },
    error: { bg: colors.error, border: colors.error },
    warning: { bg: colors.warning, border: colors.warning },
    success: { bg: colors.success, border: colors.success },
    info: { bg: colors.info, border: colors.info },
  };

  const colorSet = stateColors[state];

  return {
    backgroundColor: variant === 'filled' ? colorSet.bg : 'transparent',
    borderColor: colorSet.border,
  };
}

// Get text color based on variant and state
function getTextStyle(
  variant: ButtonVariant,
  state: ButtonState,
  disabled: boolean,
  colors: ColorScheme
): TextStyle {
  if (disabled) {
    return {
      color: colors.textSecondary,
    };
  }

  if (variant === 'outlined') {
    const stateTextColors: Record<ButtonState, string> = {
      primary: colors.accent,
      neutral: colors.textPrimary,
      error: colors.error,
      warning: colors.warning,
      success: colors.success,
      info: colors.info,
    };
    return { color: stateTextColors[state] };
  }

  // Filled variant - ensure good contrast
  // Dark text on warning (yellow/orange), white text on others
  const darkText = state === 'warning';
  return {
    color: darkText ? colors.background : colors.textPrimary,
  };
}

// Get loader color for ActivityIndicator
function getLoaderColor(variant: ButtonVariant, state: ButtonState, colors: ColorScheme): string {
  if (variant === 'outlined') {
    const stateColors: Record<ButtonState, string> = {
      primary: colors.accent,
      neutral: colors.textPrimary,
      error: colors.error,
      warning: colors.warning,
      success: colors.success,
      info: colors.info,
    };
    return stateColors[state];
  }

  // Filled variant
  return state === 'warning' ? colors.background : colors.textPrimary;
}

const styles = StyleSheet.create({
  button: {
    borderRadius: 8,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 48,
  },
  text: {
    fontSize: 16,
    fontWeight: '600',
  },
});
