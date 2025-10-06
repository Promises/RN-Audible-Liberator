import React, { useState } from 'react';
import { View, Text, FlatList, StyleSheet, TouchableOpacity } from 'react-native';
import { colors, spacing, typography } from '../styles/theme';

type AudiobookStatus = 'downloaded' | 'downloading' | 'available' | 'error';

interface Audiobook {
  id: string;
  title: string;
  author: string;
  duration: string;
  status: AudiobookStatus;
  progress?: number;
}

const MOCK_DATA: Audiobook[] = [
  {
    id: '1',
    title: 'Sample Audiobook 1',
    author: 'John Doe',
    duration: '12h 34m',
    status: 'downloaded',
  },
  {
    id: '2',
    title: 'Sample Audiobook 2',
    author: 'Jane Smith',
    duration: '8h 15m',
    status: 'downloading',
    progress: 45,
  },
  {
    id: '3',
    title: 'Sample Audiobook 3',
    author: 'Bob Johnson',
    duration: '15h 42m',
    status: 'available',
  },
];

export default function LibraryScreen() {
  const [audiobooks] = useState<Audiobook[]>(MOCK_DATA);

  const getStatusColor = (status: AudiobookStatus): string => {
    switch (status) {
      case 'downloaded':
        return colors.success;
      case 'downloading':
        return colors.accent;
      case 'available':
        return colors.textSecondary;
      case 'error':
        return colors.error;
    }
  };

  const getStatusText = (book: Audiobook): string => {
    switch (book.status) {
      case 'downloaded':
        return '✓ Downloaded';
      case 'downloading':
        return `↓ ${book.progress}%`;
      case 'available':
        return 'Available';
      case 'error':
        return '✗ Error';
    }
  };

  const renderItem = ({ item }: { item: Audiobook }) => (
    <TouchableOpacity style={styles.item}>
      <View style={styles.itemContent}>
        <Text style={styles.title} numberOfLines={2}>
          {item.title}
        </Text>
        <Text style={styles.author} numberOfLines={1}>
          {item.author}
        </Text>
        <View style={styles.metadata}>
          <Text style={styles.duration}>{item.duration}</Text>
          <Text
            style={[
              styles.status,
              { color: getStatusColor(item.status) },
            ]}
          >
            {getStatusText(item)}
          </Text>
        </View>
      </View>
    </TouchableOpacity>
  );

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Library</Text>
        <Text style={styles.headerSubtitle}>
          {audiobooks.length} audiobooks
        </Text>
      </View>

      {audiobooks.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyText}>No audiobooks yet</Text>
          <Text style={styles.emptySubtext}>
            Sign in to sync your Audible library
          </Text>
        </View>
      ) : (
        <FlatList
          data={audiobooks}
          renderItem={renderItem}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.list}
          ItemSeparatorComponent={() => <View style={styles.separator} />}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  header: {
    padding: spacing.lg,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  headerTitle: {
    ...typography.title,
  },
  headerSubtitle: {
    ...typography.caption,
    marginTop: spacing.xs,
  },
  list: {
    padding: spacing.md,
  },
  item: {
    backgroundColor: colors.backgroundSecondary,
    borderRadius: 8,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: colors.border,
  },
  itemContent: {
    gap: spacing.xs,
  },
  title: {
    ...typography.subtitle,
    fontSize: 16,
  },
  author: {
    ...typography.caption,
  },
  metadata: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  duration: {
    ...typography.caption,
    fontFamily: 'monospace',
  },
  status: {
    ...typography.caption,
    fontWeight: '600',
  },
  separator: {
    height: spacing.sm,
  },
  emptyState: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.xl,
  },
  emptyText: {
    ...typography.subtitle,
    marginBottom: spacing.sm,
  },
  emptySubtext: {
    ...typography.caption,
    textAlign: 'center',
  },
});
