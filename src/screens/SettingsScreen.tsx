import React, { useState, useEffect } from 'react';
import { View, Text, TouchableOpacity, ScrollView, Switch, Alert, Platform } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useStyles } from '../hooks/useStyles';
import { useTheme } from '../styles/theme';
import type { Theme } from '../hooks/useStyles';
import { Directory, File, Paths } from 'expo-file-system';
import * as SecureStore from 'expo-secure-store';

const DOWNLOAD_PATH_KEY = 'download_path';
const AUTO_DOWNLOAD_KEY = 'auto_download';
const WIFI_ONLY_KEY = 'wifi_only';
const REMOVE_DRM_KEY = 'remove_drm';

export default function SettingsScreen() {
  const styles = useStyles(createStyles);
  const { colors } = useTheme(); // For Switch components
  const [downloadPath, setDownloadPath] = useState<string | null>(null);
  const [autoDownload, setAutoDownload] = useState(false);
  const [wifiOnly, setWifiOnly] = useState(true);
  const [removeDRM, setRemoveDRM] = useState(true);
  const [isLoading, setIsLoading] = useState(true);

  // Load saved settings on mount
  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    try {
      const [savedPath, savedAuto, savedWifi, savedDRM] = await Promise.all([
        SecureStore.getItemAsync(DOWNLOAD_PATH_KEY),
        SecureStore.getItemAsync(AUTO_DOWNLOAD_KEY),
        SecureStore.getItemAsync(WIFI_ONLY_KEY),
        SecureStore.getItemAsync(REMOVE_DRM_KEY),
      ]);

      if (savedPath) setDownloadPath(savedPath);
      if (savedAuto !== null) setAutoDownload(savedAuto === 'true');
      if (savedWifi !== null) setWifiOnly(savedWifi === 'true');
      if (savedDRM !== null) setRemoveDRM(savedDRM === 'true');
    } catch (error) {
      console.error('[Settings] Failed to load settings:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const saveSettings = async (key: string, value: string) => {
    try {
      await SecureStore.setItemAsync(key, value);
    } catch (error) {
      console.error('[Settings] Failed to save setting:', key, error);
    }
  };

  const handleChooseDirectory = async () => {
    try {
      // Use the new Directory.pickDirectoryAsync API
      const selectedDirectory = await Directory.pickDirectoryAsync(
        Platform.OS === 'android' ? undefined : Paths.document?.uri
      );

      if (selectedDirectory) {
        const selectedUri = selectedDirectory.uri;
        setDownloadPath(selectedUri);
        await saveSettings(DOWNLOAD_PATH_KEY, selectedUri);
        Alert.alert('Success', `Download directory updated successfully\n\n${(selectedDirectory as any).name || 'Selected directory'}`);
      }
    } catch (error: any) {
      console.error('[Settings] Directory picker error:', error);
      Alert.alert('Error', error.message || 'Failed to select directory');
    }
  };

  const handleAutoDownloadChange = async (value: boolean) => {
    setAutoDownload(value);
    await saveSettings(AUTO_DOWNLOAD_KEY, value.toString());
  };

  const handleWifiOnlyChange = async (value: boolean) => {
    setWifiOnly(value);
    await saveSettings(WIFI_ONLY_KEY, value.toString());
  };

  const handleRemoveDRMChange = async (value: boolean) => {
    setRemoveDRM(value);
    await saveSettings(REMOVE_DRM_KEY, value.toString());
  };

  const getDisplayPath = (path: string | null): string => {
    if (!path) return 'Not set';

    // For Android SAF URIs, extract the readable part
    if (path.includes('content://')) {
      // Extract the last part of the URI for display
      const parts = path.split('%2F');
      const lastPart = parts[parts.length - 1];
      return decodeURIComponent(lastPart || 'Selected directory');
    }

    return path;
  };

  const handleDeleteDatabase = () => {
    Alert.alert(
      'Delete Database',
      'This will delete all synced library data. You will need to sync again from your Audible account.\n\nAre you sure?',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => {
            try {
              console.log('[Settings] Deleting database files...');

              // Delete main database file
              const dbFile = new File(Paths.cache, 'audible.db');
              console.log('[Settings] Database exists:', dbFile.exists);
              if (dbFile.exists) {
                await dbFile.delete();
                console.log('[Settings] Deleted audible.db');
              }

              // Delete WAL file
              const walFile = new File(Paths.cache, 'audible.db-wal');
              console.log('[Settings] WAL exists:', walFile.exists);
              if (walFile.exists) {
                await walFile.delete();
                console.log('[Settings] Deleted audible.db-wal');
              }

              // Delete SHM file
              const shmFile = new File(Paths.cache, 'audible.db-shm');
              console.log('[Settings] SHM exists:', shmFile.exists);
              if (shmFile.exists) {
                await shmFile.delete();
                console.log('[Settings] Deleted audible.db-shm');
              }

              Alert.alert(
                'Success',
                'Database deleted successfully. Go to the Account tab to sync your library again.',
              );
              console.log('[Settings] Database deletion complete');
            } catch (error: any) {
              console.error('[Settings] Failed to delete database:', error);
              Alert.alert('Error', error.message || 'Failed to delete database');
            }
          },
        },
      ],
    );
  };

  return (
    <SafeAreaView style={styles.container} edges={['top', 'left', 'right']}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>Settings</Text>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Storage</Text>

          <View style={styles.settingItem}>
            <View style={styles.settingInfo}>
              <Text style={styles.settingLabel}>Download Directory</Text>
              <Text style={styles.settingValue} numberOfLines={1}>
                {getDisplayPath(downloadPath)}
              </Text>
            </View>
            <TouchableOpacity
              style={styles.button}
              onPress={handleChooseDirectory}
              disabled={isLoading}
            >
              <Text style={styles.buttonText}>Choose</Text>
            </TouchableOpacity>
          </View>
          {downloadPath && (
            <Text style={styles.settingHint}>
              Full path: {downloadPath}
            </Text>
          )}
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
              onValueChange={handleAutoDownloadChange}
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
              onValueChange={handleWifiOnlyChange}
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
              onValueChange={handleRemoveDRMChange}
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
          <Text style={styles.sectionTitle}>Database</Text>

          <TouchableOpacity
            style={[styles.button, styles.dangerButton]}
            onPress={handleDeleteDatabase}
          >
            <Text style={[styles.buttonText, styles.dangerButtonText]}>
              Delete Database
            </Text>
          </TouchableOpacity>
          <Text style={styles.dangerDescription}>
            Removes all synced library data. You'll need to sync again from Account tab.
          </Text>
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
      </ScrollView>
    </SafeAreaView>
  );
}

// Styles factory function
const createStyles = (theme: Theme) => ({
  container: {
    flex: 1,
    backgroundColor: theme.colors.background,
  },
  content: {
    padding: theme.spacing.lg,
    flexGrow: 1,
  },
  title: {
    ...theme.typography.title,
    marginBottom: theme.spacing.lg,
  },
  section: {
    marginBottom: theme.spacing.xl,
  },
  sectionTitle: {
    ...theme.typography.subtitle,
    marginBottom: theme.spacing.md,
  },
  settingItem: {
    flexDirection: 'row' as const,
    justifyContent: 'space-between' as const,
    alignItems: 'center' as const,
    backgroundColor: theme.colors.backgroundSecondary,
    padding: theme.spacing.md,
    borderRadius: 8,
    marginBottom: theme.spacing.sm,
    borderWidth: 1,
    borderColor: theme.colors.border,
  },
  settingInfo: {
    flex: 1,
    marginRight: theme.spacing.md,
  },
  settingLabel: {
    ...theme.typography.body,
    fontWeight: '600' as const,
    marginBottom: theme.spacing.xs,
  },
  settingValue: {
    ...theme.typography.caption,
    fontFamily: 'monospace',
  },
  settingDescription: {
    ...theme.typography.caption,
  },
  settingHint: {
    ...theme.typography.caption,
    marginTop: theme.spacing.xs,
    marginLeft: theme.spacing.md,
    fontFamily: 'monospace',
    fontSize: 11,
  },
  button: {
    backgroundColor: theme.colors.backgroundTertiary,
    paddingHorizontal: theme.spacing.md,
    paddingVertical: theme.spacing.sm,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: theme.colors.border,
  },
  buttonText: {
    ...theme.typography.body,
    fontSize: 14,
  },
  card: {
    backgroundColor: theme.colors.backgroundSecondary,
    padding: theme.spacing.md,
    borderRadius: 8,
    marginBottom: theme.spacing.sm,
    borderWidth: 1,
    borderColor: theme.colors.border,
  },
  cardLabel: {
    ...theme.typography.caption,
    marginBottom: theme.spacing.xs,
  },
  cardValue: {
    ...theme.typography.body,
    fontWeight: '600' as const,
    marginBottom: theme.spacing.xs,
  },
  cardDescription: {
    ...theme.typography.caption,
  },
  dangerButton: {
    backgroundColor: theme.colors.backgroundSecondary,
    borderColor: theme.colors.error,
  },
  dangerButtonText: {
    color: theme.colors.error,
  },
  dangerDescription: {
    ...theme.typography.caption,
    marginTop: theme.spacing.xs,
    color: theme.colors.textSecondary,
    textAlign: 'center' as const,
  },
});
