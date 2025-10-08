import React from 'react';
import { View, Text, TouchableOpacity, ScrollView } from 'react-native';
import { useStyles } from '../hooks/useStyles';
import type { Theme } from '../hooks/useStyles';
import type { Locale } from '../types/auth';

/**
 * Available Audible locales/regions
 * Each locale corresponds to a specific Audible marketplace
 */
const LOCALES: Locale[] = [
  { code: 'us', name: 'United States', flag: '🇺🇸' },
  { code: 'uk', name: 'United Kingdom', flag: '🇬🇧' },
  { code: 'de', name: 'Germany', flag: '🇩🇪' },
  { code: 'fr', name: 'France', flag: '🇫🇷' },
  { code: 'ca', name: 'Canada', flag: '🇨🇦' },
  { code: 'au', name: 'Australia', flag: '🇦🇺' },
  { code: 'it', name: 'Italy', flag: '🇮🇹' },
  { code: 'es', name: 'Spain', flag: '🇪🇸' },
  { code: 'in', name: 'India', flag: '🇮🇳' },
  { code: 'jp', name: 'Japan', flag: '🇯🇵' },
];

interface LocaleSelectorProps {
  selectedLocale: string;
  onSelect: (localeCode: string) => void;
}

/**
 * Locale selector component for choosing Audible region
 *
 * Displays a list of available Audible marketplaces with country flags.
 * The selected locale is highlighted and used for OAuth authentication.
 */
export default function LocaleSelector({ selectedLocale, onSelect }: LocaleSelectorProps) {
  const styles = useStyles(createStyles);

  return (
    <ScrollView style={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.content}>
        <Text style={styles.title}>Select your Audible region</Text>
        <Text style={styles.subtitle}>
          Choose the region where your Audible account is registered
        </Text>

        <View style={styles.localeList}>
          {LOCALES.map(locale => (
            <TouchableOpacity
              key={locale.code}
              style={[
                styles.localeButton,
                selectedLocale === locale.code && styles.localeButtonSelected
              ]}
              onPress={() => onSelect(locale.code)}
              activeOpacity={0.7}
            >
              <View style={styles.localeContent}>
                <Text style={styles.flag}>{locale.flag}</Text>
                <Text style={[
                  styles.localeName,
                  selectedLocale === locale.code && styles.localeNameSelected
                ]}>
                  {locale.name}
                </Text>
              </View>
              {selectedLocale === locale.code && (
                <Text style={styles.checkmark}>✓</Text>
              )}
            </TouchableOpacity>
          ))}
        </View>
      </View>
    </ScrollView>
  );
}

const createStyles = (theme: Theme) => ({
  container: {
    flex: 1,
  },
  content: {
    padding: theme.spacing.lg,
  },
  title: {
    ...theme.typography.title,
    fontSize: 24,
    marginBottom: theme.spacing.sm,
  },
  subtitle: {
    ...theme.typography.caption,
    marginBottom: theme.spacing.xl,
  },
  localeList: {
    gap: theme.spacing.sm,
  },
  localeButton: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    justifyContent: 'space-between' as const,
    backgroundColor: theme.colors.backgroundSecondary,
    padding: theme.spacing.md,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: theme.colors.border,
  },
  localeButtonSelected: {
    backgroundColor: theme.colors.backgroundTertiary,
    borderColor: theme.colors.accent,
  },
  localeContent: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    gap: theme.spacing.md,
  },
  flag: {
    fontSize: 24,
  },
  localeName: {
    ...theme.typography.body,
  },
  localeNameSelected: {
    fontWeight: '600' as const,
  },
  checkmark: {
    fontSize: 20,
    color: theme.colors.accent,
  },
});
