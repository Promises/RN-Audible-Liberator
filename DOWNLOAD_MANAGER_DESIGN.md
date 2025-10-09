# Download & Conversion Manager Design

## Overview

This document describes the architecture for a robust download and conversion system that supports queuing, pause/resume, progress tracking, and background operation.

## Architecture

### Components

1. **Rust Download Manager** (`native/rust-core/src/download/manager.rs`)
   - Manages encrypted file downloads from Audible
   - Queue-based with configurable concurrency
   - Resumable downloads with Range headers
   - Progress tracking via callbacks

2. **Kotlin Conversion Manager** (`modules/expo-rust-bridge/android/.../ConversionManager.kt`)
   - Manages FFmpeg-Kit decryption/conversion
   - Queue-based with single-threaded conversion
   - Progress tracking via FFmpeg callbacks
   - SAF support for output directories

3. **Kotlin Download Service** (`modules/expo-rust-bridge/android/.../DownloadService.kt`)
   - Android Foreground Service
   - Orchestrates download → conversion pipeline
   - Persistent notification with progress
   - Lifecycle management

4. **JNI Bridge Extensions** (`native/rust-core/src/jni_bridge.rs`)
   - Manager control functions (enqueue, pause, resume, cancel)
   - Status query functions
   - Progress callback bridge

5. **TypeScript Interface** (`modules/expo-rust-bridge/index.ts`)
   - High-level manager API
   - Progress event emitters
   - Queue status queries

## Data Models

### Download Task State

```typescript
type DownloadTask = {
  taskId: string;              // UUID
  asin: string;                // Book ASIN
  title: string;               // Book title
  status: 'queued' | 'downloading' | 'paused' | 'completed' | 'failed' | 'cancelled';
  progress: {
    bytesDownloaded: number;
    totalBytes: number;
    percentage: number;
    speedBps: number;          // Bytes per second
    estimatedSecondsRemaining: number;
  };
  downloadPath: string;        // Cache path for encrypted file
  outputPath: string;          // Final path for decrypted file
  error?: string;
  createdAt: string;           // ISO timestamp
  startedAt?: string;
  completedAt?: string;
};

type ConversionTask = {
  taskId: string;              // UUID
  asin: string;
  title: string;
  status: 'queued' | 'converting' | 'paused' | 'completed' | 'failed' | 'cancelled';
  progress: {
    percentage: number;
    currentTime: number;       // FFmpeg progress time
    duration: number;          // Total duration
    speedRatio: number;        // Conversion speed (1.0 = real-time)
  };
  inputPath: string;           // Encrypted file
  outputPath: string;          // Decrypted M4B
  aaxcKey: string;
  aaxcIv: string;
  error?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
};
```

### Manager State (Persisted)

**Rust SQLite Schema (`download_tasks` table)**:
```sql
CREATE TABLE download_tasks (
    task_id TEXT PRIMARY KEY,
    asin TEXT NOT NULL,
    title TEXT NOT NULL,
    status TEXT NOT NULL,
    bytes_downloaded INTEGER NOT NULL DEFAULT 0,
    total_bytes INTEGER NOT NULL DEFAULT 0,
    download_url TEXT NOT NULL,
    download_path TEXT NOT NULL,
    output_path TEXT NOT NULL,
    request_headers TEXT NOT NULL, -- JSON
    error TEXT,
    created_at TEXT NOT NULL,
    started_at TEXT,
    completed_at TEXT
);
```

**Kotlin SharedPreferences (`conversion_tasks`)**:
```kotlin
// JSON array stored as string
data class ConversionTaskState(
    val taskId: String,
    val asin: String,
    val title: String,
    val status: String,
    val inputPath: String,
    val outputPath: String,
    val aaxcKey: String,
    val aaxcIv: String,
    val createdAt: String
)
```

## API Design

### Rust Download Manager API

```rust
impl DownloadManager {
    // Create/restore manager from database
    pub async fn new(db_path: &str, config: DownloadConfig) -> Result<Self>;

    // Queue a download
    pub async fn enqueue_download(
        &self,
        asin: String,
        title: String,
        download_url: String,
        total_bytes: u64,
        output_path: String,
        request_headers: HashMap<String, String>,
    ) -> Result<String>; // Returns task_id

    // Control operations
    pub async fn pause_download(&self, task_id: &str) -> Result<()>;
    pub async fn resume_download(&self, task_id: &str) -> Result<()>;
    pub async fn cancel_download(&self, task_id: &str) -> Result<()>;
    pub async fn retry_download(&self, task_id: &str) -> Result<()>;

    // Status queries
    pub async fn get_task(&self, task_id: &str) -> Result<DownloadTask>;
    pub async fn list_tasks(&self, filter: Option<TaskStatus>) -> Result<Vec<DownloadTask>>;
    pub async fn get_active_count(&self) -> usize;

    // Progress callback registration
    pub async fn register_progress_callback(
        &self,
        task_id: String,
        callback: Box<dyn Fn(DownloadProgress) + Send + Sync>,
    );
}
```

### Kotlin Conversion Manager API

```kotlin
class ConversionManager(context: Context) {
    // Queue a conversion
    fun enqueueConversion(
        taskId: String,
        asin: String,
        title: String,
        inputPath: String,
        outputPath: String,
        aaxcKey: String,
        aaxcIv: String
    ): String // Returns task_id

    // Control operations
    fun pauseConversion(taskId: String)
    fun resumeConversion(taskId: String)
    fun cancelConversion(taskId: String)

    // Status queries
    fun getTask(taskId: String): ConversionTask?
    fun listTasks(filter: TaskStatus? = null): List<ConversionTask>
    fun getActiveCount(): Int

    // Progress callback
    fun setProgressListener(listener: (ConversionTask) -> Unit)
}
```

### Kotlin Download Service API

```kotlin
class DownloadService : Service() {
    companion object {
        // Start service and enqueue download
        fun enqueueBook(
            context: Context,
            account: Account,
            asin: String,
            title: String,
            outputDirectory: String,
            quality: String
        )

        // Control operations
        fun pauseTask(context: Context, taskId: String)
        fun resumeTask(context: Context, taskId: String)
        fun cancelTask(context: Context, taskId: String)

        // Query status
        fun getTaskStatus(context: Context, taskId: String): DownloadTask?
        fun getAllTasks(context: Context): List<DownloadTask>
    }
}
```

### JNI Bridge Functions

```rust
// Manager lifecycle
#[no_mangle]
pub extern "C" fn Java_..._nativeInitDownloadManager(
    db_path: String,
    max_concurrent: i32,
) -> String; // Returns JSON response

// Download control
#[no_mangle]
pub extern "C" fn Java_..._nativeEnqueueDownload(
    params_json: String,
) -> String; // Returns task_id

#[no_mangle]
pub extern "C" fn Java_..._nativePauseDownload(task_id: String) -> String;

#[no_mangle]
pub extern "C" fn Java_..._nativeResumeDownload(task_id: String) -> String;

#[no_mangle]
pub extern "C" fn Java_..._nativeCancelDownload(task_id: String) -> String;

// Status queries
#[no_mangle]
pub extern "C" fn Java_..._nativeGetDownloadTask(task_id: String) -> String;

#[no_mangle]
pub extern "C" fn Java_..._nativeListDownloadTasks(
    filter: String, // "all", "active", "completed", "failed"
) -> String;

// Progress callback (called from Rust → JNI → Kotlin)
// Kotlin must register a global callback handler
```

### TypeScript Interface

```typescript
// High-level API
export class DownloadManager {
  // Enqueue a book for download + conversion
  static async enqueueBook(
    account: Account,
    asin: string,
    title: string,
    outputDirectory: string,
    quality: DownloadQuality
  ): Promise<string>; // Returns task_id

  // Control operations
  static pauseTask(taskId: string): void;
  static resumeTask(taskId: string): void;
  static cancelTask(taskId: string): void;

  // Status queries
  static async getTask(taskId: string): Promise<DownloadTask>;
  static async listTasks(filter?: TaskStatus): Promise<DownloadTask[]>;

  // Progress events
  static addProgressListener(
    callback: (task: DownloadTask) => void
  ): Subscription;
}
```

## Implementation Flow

### Enqueue Download Flow

```
1. UI calls DownloadManager.enqueueBook(account, asin, ...)
2. TypeScript starts DownloadService via Intent
3. DownloadService:
   a. Fetches download license from Audible API (via Rust)
   b. Calls nativeEnqueueDownload() to queue in Rust
   c. Rust creates DownloadTask in SQLite, starts worker
   d. Returns task_id to TypeScript
4. UI receives task_id, subscribes to progress events
```

### Download Progress Flow

```
1. Rust download worker makes progress
2. Calls JNI callback: callKotlinProgressCallback(task_id, progress_json)
3. Kotlin DownloadService receives callback
4. Emits event via EventEmitter to TypeScript
5. TypeScript notifies UI via React state update
6. UI updates progress bar/notification
```

### Download Complete → Conversion Flow

```
1. Rust download completes, marks task as "completed"
2. Emits JNI callback with status="completed"
3. Kotlin DownloadService:
   a. Receives download complete event
   b. Automatically enqueues conversion task
   c. ConversionManager starts FFmpeg-Kit
4. FFmpeg-Kit progress callbacks update ConversionTask state
5. On conversion complete:
   a. Copy decrypted file to user's SAF directory
   b. Update database with final file path
   c. Emit completion event to UI
   d. Clean up cache files
```

### Background Operation Flow

```
1. User backgrounds app
2. DownloadService continues (Foreground Service)
3. Persistent notification shows:
   - Current book downloading/converting
   - Progress bar
   - Pause/Cancel buttons
4. On completion:
   - Show "Download Complete" notification
   - Allow user to tap to open app
```

### App Restart Flow

```
1. App is killed/restarted
2. On app start:
   a. DownloadService starts automatically (if tasks exist)
   b. Rust DownloadManager loads tasks from SQLite
   c. Kotlin ConversionManager loads tasks from SharedPreferences
   d. Resume any "downloading" or "converting" tasks
3. UI queries all tasks and displays status
```

## Persistence Strategy

### Rust SQLite

- Store all download tasks permanently
- Track partial download progress (bytes_downloaded)
- Enable resume from byte offset

### Kotlin SharedPreferences

- Store conversion queue (lightweight)
- Clear completed conversions after 24 hours
- Keep failed conversions for retry

### File Cleanup

- Delete encrypted cache files after conversion
- Keep decrypted files in user's directory
- Clean up abandoned files on app start

## Progress Tracking

### Download Progress

```rust
struct DownloadProgress {
    task_id: String,
    bytes_downloaded: u64,
    total_bytes: u64,
    speed_bps: u64,
    eta_seconds: u64,
}
```

Calculated using:
- reqwest streaming with content-length
- Tokio interval timer (1 second)
- Exponential moving average for speed

### Conversion Progress

```kotlin
// FFmpeg-Kit provides time-based progress
val progressCallback = { statistics: Statistics ->
    val currentTime = statistics.time // milliseconds
    val percentage = (currentTime / totalDuration) * 100
    updateTask(taskId, percentage)
}
```

## Error Handling

### Download Errors

- **Network errors**: Auto-retry with exponential backoff (3 attempts)
- **Disk full**: Pause task, notify user
- **Invalid license**: Mark as failed, no retry

### Conversion Errors

- **FFmpeg errors**: Mark as failed, keep encrypted file for manual retry
- **Invalid keys**: Mark as failed, no retry
- **Disk full**: Pause task, notify user

### Recovery

- On app restart: Resume "downloading" tasks
- On network reconnect: Resume "paused" tasks (optional)

## Notification Design

### Active Download Notification

```
Title: "Downloading 2 audiobooks"
Text: "The Martian by Andy Weir (45%)"
Progress: 45/100
Actions: [Pause] [Cancel]
```

### Conversion Notification

```
Title: "Converting The Martian"
Text: "Decrypting audio... (67%)"
Progress: 67/100
Actions: [Cancel]
```

### Completion Notification

```
Title: "Download Complete"
Text: "The Martian is ready to listen"
Actions: [Open App]
```

## Testing Strategy

### Unit Tests

- Rust: Download manager queue operations
- Kotlin: Conversion manager state transitions
- TypeScript: API surface mocking

### Integration Tests

- End-to-end: Enqueue → Download → Convert → Complete
- Pause/Resume: Verify partial state persistence
- Cancellation: Verify cleanup

### Manual Tests

- Background operation: Verify downloads continue
- App restart: Verify queue restoration
- Network disconnect: Verify auto-resume
- Low storage: Verify graceful failure

## Performance Considerations

### Concurrency

- **Downloads**: Max 3 concurrent (configurable)
- **Conversions**: Max 1 (FFmpeg-Kit is CPU-intensive)

### Memory

- Stream downloads (no full file in memory)
- Limit progress callback frequency (max 1/second)

### Battery

- Use WorkManager for non-urgent downloads (future)
- Allow user to pause all downloads

## Security Considerations

- Store decryption keys in memory only (never persist AAXC keys)
- Clean up cache files immediately after conversion
- Validate file paths to prevent directory traversal

## Future Enhancements

1. **WiFi-only mode**: Only download on WiFi
2. **Auto-download**: Download entire library automatically
3. **Priority queue**: Allow user to reorder queue
4. **Batch operations**: Pause/resume all tasks
5. **iOS support**: Extend to iOS with similar architecture
