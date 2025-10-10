import React, {useState, useEffect, useRef} from 'react';
import {View, Text, FlatList, TouchableOpacity, RefreshControl, Image, Alert, ActivityIndicator, Platform, PermissionsAndroid} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import {useStyles} from '../hooks/useStyles';
import {useTheme} from '../styles/theme';
import type {Theme} from '../hooks/useStyles';
import {
    getBooks,
    initializeDatabase,
    refreshToken,
    enqueueDownload,
    listDownloadTasks,
    pauseDownload,
    resumeDownload,
    cancelDownload,
} from '../../modules/expo-rust-bridge';
import type {Book, Account, DownloadTask} from '../../modules/expo-rust-bridge';
import {Paths} from 'expo-file-system';
import * as SecureStore from 'expo-secure-store';

const DOWNLOAD_PATH_KEY = 'download_path';

export default function LibraryScreen() {
    const styles = useStyles(createStyles);
    const { colors } = useTheme();
    const [audiobooks, setAudiobooks] = useState<Book[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isRefreshing, setIsRefreshing] = useState(false);
    const [isLoadingMore, setIsLoadingMore] = useState(false);
    const [totalCount, setTotalCount] = useState(0);
    const [hasMore, setHasMore] = useState(true);
    const [downloadTasks, setDownloadTasks] = useState<Map<string, DownloadTask>>(new Map());
    const progressInterval = useRef<NodeJS.Timeout | null>(null);

    // Load books from database on mount
    useEffect(() => {
        loadBooks(true);
    }, []);

    // Poll for download progress
    useEffect(() => {
        const pollProgress = () => {
            try {
                const cacheUri = Paths.cache.uri;
                const cachePath = cacheUri.replace('file://', '');
                const dbPath = `${cachePath.replace(/\/$/, '')}/audible.db`;

                const tasks = listDownloadTasks(dbPath);
                const taskMap = new Map<string, DownloadTask>();

                tasks.forEach(task => {
                    taskMap.set(task.asin, task);
                });

                setDownloadTasks(taskMap);
            } catch (error) {
                console.error('[LibraryScreen] Error polling progress:', error);
            }
        };

        // Initial poll
        pollProgress();

        // Poll every 2 seconds
        progressInterval.current = setInterval(pollProgress, 2000);

        return () => {
            if (progressInterval.current) {
                clearInterval(progressInterval.current);
            }
        };
    }, []);

    const loadBooks = async (reset: boolean = false) => {
        try {
            const cacheUri = Paths.cache.uri;
            const cachePath = cacheUri.replace('file://', '');
            const dbPath = `${cachePath.replace(/\/$/, '')}/audible.db`;

            console.log('[LibraryScreen] Loading books from:', dbPath);

            // Initialize database first
            try {
                initializeDatabase(dbPath);
            } catch (dbError) {
                console.log('[LibraryScreen] Database not initialized yet');
                setAudiobooks([]);
                setTotalCount(0);
                setHasMore(false);
                return;
            }

            const offset = reset ? 0 : audiobooks.length;
            const limit = 100;

            console.log('[LibraryScreen] Fetching books:', { offset, limit });
            const response = getBooks(dbPath, offset, limit);
            console.log('[LibraryScreen] Loaded books:', response.books.length, 'of', response.total_count);

            if (reset) {
                setAudiobooks(response.books);
            } else {
                setAudiobooks(prev => [...prev, ...response.books]);
            }

            setTotalCount(response.total_count);
            setHasMore(offset + response.books.length < response.total_count);
        } catch (error) {
            console.error('[LibraryScreen] Error loading books:', error);
            if (reset) {
                setAudiobooks([]);
                setTotalCount(0);
            }
            setHasMore(false);
        } finally {
            setIsLoading(false);
            setIsRefreshing(false);
            setIsLoadingMore(false);
        }
    };

    const handleRefresh = () => {
        setIsRefreshing(true);
        setHasMore(true);
        loadBooks(true);
    };

    const handleLoadMore = () => {
        if (!isLoadingMore && !isLoading && hasMore) {
            console.log('[LibraryScreen] Loading more books...');
            setIsLoadingMore(true);
            loadBooks(false);
        }
    };

    const formatDuration = (seconds: number): string => {
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        return `${hours}h ${minutes}m`;
    };

    const getCoverUrl = (book: Book): string | null => {
        if (!book.cover_url) return null;
        // Replace _SL500_ with _SL150_ for smaller cover images
        return book.cover_url.replace(/_SL\d+_/, '_SL150_');
    };

    const getStatus = (book: Book): { text: string; color: string } => {
        const task = downloadTasks.get(book.audible_product_id);

        if (task) {
            const percentage = task.total_bytes > 0
                ? ((task.bytes_downloaded / task.total_bytes) * 100).toFixed(1)
                : '0.0';

            switch (task.status) {
                case 'queued':
                    return {text: '⏳ Queued', color: colors.textSecondary};
                case 'downloading':
                    return {text: `⬇ ${percentage}%`, color: colors.info};
                case 'paused':
                    return {text: `⏸ Paused ${percentage}%`, color: colors.warning};
                case 'completed':
                    return {text: '✓ Downloaded', color: colors.success};
                case 'failed':
                    return {text: '✗ Failed', color: colors.error};
                case 'cancelled':
                    return {text: 'Cancelled', color: colors.textSecondary};
                default:
                    return {text: 'Available', color: colors.textSecondary};
            }
        }

        if (book.file_path) {
            return {text: '✓ Downloaded', color: colors.success};
        }

        return {text: 'Available', color: colors.textSecondary};
    };

    const requestNotificationPermission = async (): Promise<boolean> => {
        if (Platform.OS === 'android') {
            if (Platform.Version >= 33) {
                try {
                    const granted = await PermissionsAndroid.request(
                        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
                        {
                            title: 'Notification Permission',
                            message: 'LibriSync needs notification permission to show download progress',
                            buttonPositive: 'OK',
                        }
                    );
                    return granted === PermissionsAndroid.RESULTS.GRANTED;
                } catch (err) {
                    console.warn('[LibraryScreen] Notification permission error:', err);
                    return false;
                }
            }
            return true; // Android < 13 doesn't need runtime permission
        }
        return true; // iOS doesn't need this permission for foreground notifications
    };

    const handleDownload = async (book: Book) => {
        try {
            // Request notification permission first
            const hasPermission = await requestNotificationPermission();
            if (!hasPermission) {
                Alert.alert(
                    'Permission Required',
                    'Please grant notification permission to see download progress',
                    [{ text: 'OK' }]
                );
                return;
            }

            // Get account from SecureStore
            const accountData = await SecureStore.getItemAsync('audible_account');
            if (!accountData) {
                Alert.alert('Error', 'Please log in first');
                return;
            }

            let account: Account = JSON.parse(accountData);

            // Check if token is expired and refresh if needed
            if (account.identity?.access_token) {
                const expiresAt = new Date(account.identity.access_token.expires_at);
                const now = new Date();
                const minutesUntilExpiry = (expiresAt.getTime() - now.getTime()) / 1000 / 60;

                if (minutesUntilExpiry < 5) {
                    console.log('[LibraryScreen] Token expiring soon, refreshing...');
                    try {
                        const newTokens = await refreshToken(account);
                        // Update account with new tokens
                        account.identity.access_token.token = newTokens.access_token;
                        if (newTokens.refresh_token) {
                            account.identity.refresh_token = newTokens.refresh_token;
                        }
                        const newExpiresAt = new Date(Date.now() + parseInt(newTokens.expires_in.toString()) * 1000).toISOString();
                        account.identity.access_token.expires_at = newExpiresAt;

                        // Save updated account
                        await SecureStore.setItemAsync('audible_account', JSON.stringify(account));
                        console.log('[LibraryScreen] Token refreshed successfully');
                    } catch (refreshError) {
                        console.error('[LibraryScreen] Token refresh failed:', refreshError);
                        Alert.alert('Error', 'Please log in again - token refresh failed');
                        return;
                    }
                }
            }

            // Get download directory from settings
            const downloadDir = await SecureStore.getItemAsync(DOWNLOAD_PATH_KEY);

            if (!downloadDir) {
                Alert.alert(
                    'Download Directory Not Set',
                    'Please go to Settings and choose a download directory first.',
                    [{ text: 'OK' }]
                );
                return;
            }

            console.log('[LibraryScreen] Enqueueing download:', book.title, book.audible_product_id);

            // Get database path
            const cacheUri = Paths.cache.uri;
            const cachePath = cacheUri.replace('file://', '');
            const dbPath = `${cachePath.replace(/\/$/, '')}/audible.db`;

            // Enqueue download (runs in background)
            await enqueueDownload(
                dbPath,
                account,
                book.audible_product_id,
                book.title,
                downloadDir,
                'High'
            );

            console.log('[LibraryScreen] Download enqueued successfully');

            Alert.alert(
                'Download Started',
                `${book.title} has been added to the download queue. You can monitor progress here or leave the app.`
            );

        } catch (error: any) {
            console.error('[LibraryScreen] Download error:', error);
            Alert.alert('Download Failed', error.message || 'Unknown error');
        }
    };

    const handlePauseDownload = (book: Book) => {
        try {
            const cacheUri = Paths.cache.uri;
            const cachePath = cacheUri.replace('file://', '');
            const dbPath = `${cachePath.replace(/\/$/, '')}/audible.db`;

            const task = downloadTasks.get(book.audible_product_id);
            if (task) {
                pauseDownload(dbPath, task.task_id);
                console.log('[LibraryScreen] Paused download:', book.title);
            }
        } catch (error) {
            console.error('[LibraryScreen] Pause error:', error);
        }
    };

    const handleResumeDownload = (book: Book) => {
        try {
            const cacheUri = Paths.cache.uri;
            const cachePath = cacheUri.replace('file://', '');
            const dbPath = `${cachePath.replace(/\/$/, '')}/audible.db`;

            const task = downloadTasks.get(book.audible_product_id);
            if (task) {
                resumeDownload(dbPath, task.task_id);
                console.log('[LibraryScreen] Resumed download:', book.title);
            }
        } catch (error) {
            console.error('[LibraryScreen] Resume error:', error);
        }
    };

    const handleCancelDownload = (book: Book) => {
        try {
            const cacheUri = Paths.cache.uri;
            const cachePath = cacheUri.replace('file://', '');
            const dbPath = `${cachePath.replace(/\/$/, '')}/audible.db`;

            const task = downloadTasks.get(book.audible_product_id);
            if (task) {
                Alert.alert(
                    'Cancel Download',
                    `Are you sure you want to cancel downloading "${book.title}"?`,
                    [
                        { text: 'No', style: 'cancel' },
                        {
                            text: 'Yes',
                            style: 'destructive',
                            onPress: () => {
                                cancelDownload(dbPath, task.task_id);
                                console.log('[LibraryScreen] Cancelled download:', book.title);
                            }
                        }
                    ]
                );
            }
        } catch (error) {
            console.error('[LibraryScreen] Cancel error:', error);
        }
    };

    const renderItem = ({item}: { item: Book }) => {
        const status = getStatus(item);
        const authorText = (item.authors?.length || 0) > 0 ? item.authors.join(', ') : 'Unknown Author';
        const coverUrl = getCoverUrl(item);
        const task = downloadTasks.get(item.audible_product_id);
        const isDownloaded = !!item.file_path || task?.status === 'completed';
        const canDownload = !task || task.status === 'failed' || task.status === 'cancelled';
        const isDownloading = task?.status === 'downloading';
        const isPaused = task?.status === 'paused';
        const isQueued = task?.status === 'queued';

        return (
            <TouchableOpacity style={styles.item} onPress={() => console.log('Item pressed:', item)}>
                <View style={styles.itemRow}>
                    {coverUrl ? (
                        <Image
                            source={{uri: coverUrl}}
                            style={styles.cover}
                            resizeMode="cover"
                        />
                    ) : (
                        <View style={styles.coverPlaceholder}>
                            <Text style={styles.coverPlaceholderText}>📚</Text>
                        </View>
                    )}
                    <View style={styles.itemContent}>
                        <Text style={styles.title} numberOfLines={2}>
                            {item.title}
                        </Text>
                        <Text style={styles.author} numberOfLines={1}>
                            {authorText}
                        </Text>
                        <View style={styles.metadata}>
                            <Text style={styles.duration}>{formatDuration(item.duration_seconds)}</Text>
                            <Text style={[styles.status, {color: status.color}]}>
                                {status.text}
                            </Text>
                        </View>
                    </View>

                    {/* Show download button if not downloaded and no active task */}
                    {!isDownloaded && canDownload && (
                        <TouchableOpacity
                            style={styles.downloadButton}
                            onPress={() => handleDownload(item)}
                        >
                            <Text style={styles.downloadButtonText}>⬇</Text>
                        </TouchableOpacity>
                    )}

                    {/* Show pause button if downloading */}
                    {isDownloading && (
                        <TouchableOpacity
                            style={styles.pauseButton}
                            onPress={() => handlePauseDownload(item)}
                        >
                            <Text style={styles.pauseButtonText}>⏸</Text>
                        </TouchableOpacity>
                    )}

                    {/* Show resume button if paused */}
                    {isPaused && (
                        <TouchableOpacity
                            style={styles.resumeButton}
                            onPress={() => handleResumeDownload(item)}
                        >
                            <Text style={styles.resumeButtonText}>▶</Text>
                        </TouchableOpacity>
                    )}

                    {/* Show cancel button if downloading/queued/paused */}
                    {(isDownloading || isPaused || isQueued) && (
                        <TouchableOpacity
                            style={styles.cancelButton}
                            onPress={() => handleCancelDownload(item)}
                        >
                            <Text style={styles.cancelButtonText}>✕</Text>
                        </TouchableOpacity>
                    )}

                    {/* Show spinner if queued */}
                    {isQueued && (
                        <View style={styles.downloadButton}>
                            <ActivityIndicator size="small" color={colors.textSecondary} />
                        </View>
                    )}
                </View>
            </TouchableOpacity>
        );
    };

    return (
        <SafeAreaView style={styles.container} edges={['top', 'left', 'right']}>
            <View style={styles.header}>
                <Text style={styles.headerTitle}>Library</Text>
                <Text style={styles.headerSubtitle}>
                    {totalCount > 0 ? `${audiobooks.length} of ${totalCount} audiobooks` : `${audiobooks.length} audiobooks`}
                </Text>
            </View>

            {isLoading ? (
                <View style={styles.emptyState}>
                    <Text style={styles.emptyText}>Loading library...</Text>
                </View>
            ) : audiobooks.length === 0 ? (
                <View style={styles.emptyState}>
                    <Text style={styles.emptyText}>No audiobooks yet</Text>
                    <Text style={styles.emptySubtext}>
                        Go to Account tab to sign in and sync your Audible library
                    </Text>
                </View>
            ) : (
                <FlatList
                    data={audiobooks}
                    renderItem={renderItem}
                    keyExtractor={(item) => item.audible_product_id}
                    contentContainerStyle={styles.list}
                    ItemSeparatorComponent={() => <View style={styles.separator}/>}
                    onEndReached={handleLoadMore}
                    onEndReachedThreshold={0.5}
                    ListFooterComponent={
                        isLoadingMore ? (
                            <View style={styles.loadingFooter}>
                                <Text style={styles.loadingText}>Loading more...</Text>
                            </View>
                        ) : null
                    }
                    refreshControl={
                        <RefreshControl
                            refreshing={isRefreshing}
                            onRefresh={handleRefresh}
                            tintColor={colors.accent}
                            colors={[colors.accent]}
                        />
                    }
                />
            )}
        </SafeAreaView>
    );
}

const createStyles = (theme: Theme) => ({
    container: {
        flex: 1,
        backgroundColor: theme.colors.background,
    },
    header: {
        padding: theme.spacing.lg,
        borderBottomWidth: 1,
        borderBottomColor: theme.colors.border,
    },
    headerTitle: {
        ...theme.typography.title,
    },
    headerSubtitle: {
        ...theme.typography.caption,
        marginTop: theme.spacing.xs,
    },
    list: {
        padding: theme.spacing.md,
    },
    item: {
        backgroundColor: theme.colors.backgroundSecondary,
        borderRadius: 8,
        padding: theme.spacing.md,
        borderWidth: 1,
        borderColor: theme.colors.border,
    },
    itemRow: {
        flexDirection: 'row' as const,
        gap: theme.spacing.md,
    },
    cover: {
        width: 80,
        height: 80,
        borderRadius: 4,
        backgroundColor: theme.colors.background,
    },
    coverPlaceholder: {
        width: 80,
        height: 80,
        borderRadius: 4,
        backgroundColor: theme.colors.background,
        justifyContent: 'center' as const,
        alignItems: 'center' as const,
    },
    coverPlaceholderText: {
        fontSize: 32,
    },
    itemContent: {
        flex: 1,
        gap: theme.spacing.xs,
    },
    title: {
        ...theme.typography.subtitle,
        fontSize: 16,
    },
    author: {
        ...theme.typography.caption,
    },
    metadata: {
        flexDirection: 'row' as const,
        justifyContent: 'space-between' as const,
        alignItems: 'center' as const,
        marginTop: theme.spacing.xs,
    },
    duration: {
        ...theme.typography.caption,
        fontFamily: 'monospace',
    },
    status: {
        ...theme.typography.caption,
        fontWeight: '600' as const,
    },
    separator: {
        height: theme.spacing.sm,
    },
    emptyState: {
        flex: 1,
        justifyContent: 'center' as const,
        alignItems: 'center' as const,
        padding: theme.spacing.xl,
    },
    emptyText: {
        ...theme.typography.subtitle,
        marginBottom: theme.spacing.sm,
    },
    emptySubtext: {
        ...theme.typography.caption,
        textAlign: 'center' as const,
    },
    loadingFooter: {
        padding: theme.spacing.md,
        alignItems: 'center' as const,
    },
    loadingText: {
        ...theme.typography.caption,
        color: theme.colors.textSecondary,
    },
    downloadButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: theme.colors.accent,
        justifyContent: 'center' as const,
        alignItems: 'center' as const,
    },
    downloadButtonText: {
        fontSize: 20,
        color: theme.colors.background,
    },
    pauseButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: theme.colors.warning,
        justifyContent: 'center' as const,
        alignItems: 'center' as const,
        marginRight: theme.spacing.xs,
    },
    pauseButtonText: {
        fontSize: 18,
        color: theme.colors.background,
    },
    resumeButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: theme.colors.success,
        justifyContent: 'center' as const,
        alignItems: 'center' as const,
        marginRight: theme.spacing.xs,
    },
    resumeButtonText: {
        fontSize: 18,
        color: theme.colors.background,
    },
    cancelButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: theme.colors.error,
        justifyContent: 'center' as const,
        alignItems: 'center' as const,
    },
    cancelButtonText: {
        fontSize: 20,
        color: theme.colors.background,
    },
});
