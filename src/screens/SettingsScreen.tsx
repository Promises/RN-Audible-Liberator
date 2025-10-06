import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, Switch } from 'react-native';
import { colors, spacing, typography } from '../styles/theme';

export default function SettingsScreen() {
  const [downloadPath, setDownloadPath] = useState('/storage/emulated/0/Audiobooks');
  const [autoDownload, setAutoDownload] = useState(false);
  const [wifiOnly, setWifiOnly] = useState(true);
  const [removeDRM, setRemoveDRM] = useState(true);

  const handleChooseDirectory = () => {
    // TODO: Implement native directory picker
    console.log('Open directory picker');
  };

  return (
    <ScrollView style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.title}>Settings</Text>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Storage</Text>

          <View style={styles.settingItem}>
            <View style={styles.settingInfo}>
              <Text style={styles.settingLabel}>Download Directory</Text>
              <Text style={styles.settingValue} numberOfLines={1}>
                {downloadPath}
              </Text>
            </View>
            <TouchableOpacity
              style={styles.button}
              onPress={handleChooseDirectory}
            >
              <Text style={styles.buttonText}>Choose</Text>
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Download Options</Text>

          <View style={styles.settingItem}>
            <View style={styles.settingInfo}>
              <Text style={styles.settingLabel}>Auto-download new books</Text>
              <Text style={styles.settingDescription}>
                Automatically download new books when they appear in your library
              </Text>
            </View>
            <Switch
              value={autoDownload}
              onValueChange={setAutoDownload}
              trackColor={{ false: colors.border, true: colors.accentDim }}
              thumbColor={autoDownload ? colors.accent : colors.textSecondary}
            />
          </View>

          <View style={styles.settingItem}>
            <View style={styles.settingInfo}>
              <Text style={styles.settingLabel}>Wi-Fi only</Text>
              <Text style={styles.settingDescription}>
                Only download over Wi-Fi connection
              </Text>
            </View>
            <Switch
              value={wifiOnly}
              onValueChange={setWifiOnly}
              trackColor={{ false: colors.border, true: colors.accentDim }}
              thumbColor={wifiOnly ? colors.accent : colors.textSecondary}
            />
          </View>
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>DRM Removal</Text>

          <View style={styles.settingItem}>
            <View style={styles.settingInfo}>
              <Text style={styles.settingLabel}>Remove DRM</Text>
              <Text style={styles.settingDescription}>
                Remove DRM from downloaded audiobooks (requires activation bytes)
              </Text>
            </View>
            <Switch
              value={removeDRM}
              onValueChange={setRemoveDRM}
              trackColor={{ false: colors.border, true: colors.accentDim }}
              thumbColor={removeDRM ? colors.accent : colors.textSecondary}
            />
          </View>

          {removeDRM && (
            <TouchableOpacity style={styles.card}>
              <Text style={styles.cardLabel}>Activation Bytes</Text>
              <Text style={styles.cardValue}>Not configured</Text>
              <Text style={styles.cardDescription}>
                Tap to configure activation bytes for DRM removal
              </Text>
            </TouchableOpacity>
          )}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>About</Text>

          <View style={styles.card}>
            <Text style={styles.cardLabel}>Version</Text>
            <Text style={styles.cardValue}>1.0.0</Text>
          </View>

          <View style={styles.card}>
            <Text style={styles.cardLabel}>Based on</Text>
            <Text style={styles.cardValue}>Libation</Text>
            <Text style={styles.cardDescription}>
              React Native port of Libation Audible client
            </Text>
          </View>
        </View>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    padding: spacing.lg,
  },
  title: {
    ...typography.title,
    marginBottom: spacing.lg,
  },
  section: {
    marginBottom: spacing.xl,
  },
  sectionTitle: {
    ...typography.subtitle,
    marginBottom: spacing.md,
  },
  settingItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: colors.backgroundSecondary,
    padding: spacing.md,
    borderRadius: 8,
    marginBottom: spacing.sm,
    borderWidth: 1,
    borderColor: colors.border,
  },
  settingInfo: {
    flex: 1,
    marginRight: spacing.md,
  },
  settingLabel: {
    ...typography.body,
    fontWeight: '600',
    marginBottom: spacing.xs,
  },
  settingValue: {
    ...typography.caption,
    fontFamily: 'monospace',
  },
  settingDescription: {
    ...typography.caption,
  },
  button: {
    backgroundColor: colors.backgroundTertiary,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: colors.border,
  },
  buttonText: {
    ...typography.body,
    fontSize: 14,
  },
  card: {
    backgroundColor: colors.backgroundSecondary,
    padding: spacing.md,
    borderRadius: 8,
    marginBottom: spacing.sm,
    borderWidth: 1,
    borderColor: colors.border,
  },
  cardLabel: {
    ...typography.caption,
    marginBottom: spacing.xs,
  },
  cardValue: {
    ...typography.body,
    fontWeight: '600',
    marginBottom: spacing.xs,
  },
  cardDescription: {
    ...typography.caption,
  },
});
