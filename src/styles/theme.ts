export const colors = {
  background: '#1a1a1a',
  backgroundSecondary: '#2a2a2a',
  backgroundTertiary: '#333333',
  textPrimary: '#ffffff',
  textSecondary: '#888888',
  accent: '#00ff00',
  accentDim: '#00aa00',
  border: '#444444',
  error: '#ff4444',
  success: '#44ff44',
  warning: '#ffaa00',
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
