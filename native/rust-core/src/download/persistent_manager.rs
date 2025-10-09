//! Persistent Download Manager with queue, pause/resume, and progress tracking
//!
//! This module implements a robust download manager that:
//! - Persists download state to SQLite
//! - Supports pause/resume with byte-range resumption
//! - Provides real-time progress tracking
//! - Handles concurrent downloads with semaphore-based control
//! - Automatically recovers from app restarts

use crate::error::{LibationError, Result};
use crate::download::progress::{DownloadProgress, DownloadState};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sqlx::{SqlitePool, Row};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use tokio::fs;
use tokio::io::AsyncWriteExt;
use tokio::sync::{RwLock, Semaphore};
use tokio::task::JoinHandle;
use uuid::Uuid;

/// Status of a download task
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::Type)]
#[sqlx(type_name = "TEXT")]
pub enum TaskStatus {
    #[serde(rename = "queued")]
    Queued,
    #[serde(rename = "downloading")]
    Downloading,
    #[serde(rename = "paused")]
    Paused,
    #[serde(rename = "completed")]
    Completed,
    #[serde(rename = "failed")]
    Failed,
    #[serde(rename = "cancelled")]
    Cancelled,
}

impl TaskStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            TaskStatus::Queued => "queued",
            TaskStatus::Downloading => "downloading",
            TaskStatus::Paused => "paused",
            TaskStatus::Completed => "completed",
            TaskStatus::Failed => "failed",
            TaskStatus::Cancelled => "cancelled",
        }
    }

    pub fn from_str(s: &str) -> Result<Self> {
        match s {
            "queued" => Ok(TaskStatus::Queued),
            "downloading" => Ok(TaskStatus::Downloading),
            "paused" => Ok(TaskStatus::Paused),
            "completed" => Ok(TaskStatus::Completed),
            "failed" => Ok(TaskStatus::Failed),
            "cancelled" => Ok(TaskStatus::Cancelled),
            _ => Err(LibationError::InvalidInput(format!("Invalid task status: {}", s))),
        }
    }
}

/// Download task representing a book download
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadTask {
    pub task_id: String,
    pub asin: String,
    pub title: String,
    pub status: TaskStatus,
    pub bytes_downloaded: u64,
    pub total_bytes: u64,
    pub download_url: String,
    pub download_path: String,
    pub output_path: String,
    #[serde(default)]
    pub request_headers: HashMap<String, String>,
    pub error: Option<String>,
    pub retry_count: i32,
    pub created_at: String,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
}

impl DownloadTask {
    /// Calculate download percentage
    pub fn progress_percentage(&self) -> f64 {
        if self.total_bytes == 0 {
            return 0.0;
        }
        (self.bytes_downloaded as f64 / self.total_bytes as f64) * 100.0
    }

    /// Check if task is terminal (completed, failed, or cancelled)
    pub fn is_terminal(&self) -> bool {
        matches!(
            self.status,
            TaskStatus::Completed | TaskStatus::Failed | TaskStatus::Cancelled
        )
    }

    /// Check if task can be resumed
    pub fn can_resume(&self) -> bool {
        matches!(self.status, TaskStatus::Paused | TaskStatus::Failed)
            && self.bytes_downloaded < self.total_bytes
    }
}

/// Progress callback function type
pub type ProgressCallback = Box<dyn Fn(DownloadTask) + Send + Sync>;

/// Active download worker handle
struct ActiveDownload {
    handle: JoinHandle<()>,
    cancel_tx: tokio::sync::oneshot::Sender<()>,
}

/// Persistent Download Manager
pub struct PersistentDownloadManager {
    pool: Arc<SqlitePool>,
    max_concurrent: usize,
    semaphore: Arc<Semaphore>,
    active_downloads: Arc<RwLock<HashMap<String, ActiveDownload>>>,
    progress_callbacks: Arc<RwLock<HashMap<String, ProgressCallback>>>,
}

impl PersistentDownloadManager {
    /// Create a new manager with existing database pool
    pub async fn new(pool: Arc<SqlitePool>, max_concurrent: usize) -> Result<Self> {
        Ok(Self {
            pool,
            max_concurrent,
            semaphore: Arc::new(Semaphore::new(max_concurrent)),
            active_downloads: Arc::new(RwLock::new(HashMap::new())),
            progress_callbacks: Arc::new(RwLock::new(HashMap::new())),
        })
    }

    /// Enqueue a new download
    pub async fn enqueue_download(
        &self,
        asin: String,
        title: String,
        download_url: String,
        total_bytes: u64,
        download_path: String,
        output_path: String,
        request_headers: HashMap<String, String>,
    ) -> Result<String> {
        let task_id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now().to_rfc3339();

        // Insert into database
        let headers_json = serde_json::to_string(&request_headers)
            .map_err(|e| LibationError::InvalidInput(format!("Invalid headers: {}", e)))?;

        sqlx::query(
            r#"
            INSERT INTO DownloadTasks (
                task_id, asin, title, status, bytes_downloaded, total_bytes,
                download_url, download_path, output_path, request_headers, created_at
            )
            VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(&task_id)
        .bind(&asin)
        .bind(&title)
        .bind(TaskStatus::Queued.as_str())
        .bind(total_bytes as i64)
        .bind(&download_url)
        .bind(&download_path)
        .bind(&output_path)
        .bind(&headers_json)
        .bind(&now)
        .execute(&*self.pool)
        .await?;

        // Auto-start download if slots available
        self.try_start_next_download().await?;

        Ok(task_id)
    }

    /// Get a task by ID
    pub async fn get_task(&self, task_id: &str) -> Result<DownloadTask> {
        let row = sqlx::query(
            "SELECT * FROM DownloadTasks WHERE task_id = ?"
        )
        .bind(task_id)
        .fetch_one(&*self.pool)
        .await
        .map_err(|_| LibationError::RecordNotFound(format!("Task not found: {}", task_id)))?;

        self.row_to_task(row)
    }

    /// List all tasks, optionally filtered by status
    pub async fn list_tasks(&self, filter: Option<TaskStatus>) -> Result<Vec<DownloadTask>> {
        let rows = if let Some(status) = filter {
            sqlx::query("SELECT * FROM DownloadTasks WHERE status = ? ORDER BY created_at DESC")
                .bind(status.as_str())
                .fetch_all(&*self.pool)
                .await?
        } else {
            sqlx::query("SELECT * FROM DownloadTasks ORDER BY created_at DESC")
                .fetch_all(&*self.pool)
                .await?
        };

        rows.into_iter()
            .map(|row| self.row_to_task(row))
            .collect()
    }

    /// Get count of active downloads
    pub async fn get_active_count(&self) -> usize {
        self.active_downloads.read().await.len()
    }

    /// Pause a download
    pub async fn pause_download(&self, task_id: &str) -> Result<()> {
        // Check if actively downloading
        let mut active = self.active_downloads.write().await;

        if let Some(download) = active.remove(task_id) {
            // Send cancellation signal
            let _ = download.cancel_tx.send(());
            drop(active);

            // Wait briefly for graceful shutdown
            let _ = tokio::time::timeout(
                tokio::time::Duration::from_secs(2),
                download.handle
            ).await;

            // Update database status
            self.update_task_status(task_id, TaskStatus::Paused).await?;
        } else {
            // Not actively downloading, just update status if queued
            let task = self.get_task(task_id).await?;
            if task.status == TaskStatus::Queued {
                self.update_task_status(task_id, TaskStatus::Paused).await?;
            }
        }

        Ok(())
    }

    /// Resume a paused download
    pub async fn resume_download(&self, task_id: &str) -> Result<()> {
        let task = self.get_task(task_id).await?;

        if !task.can_resume() {
            return Err(LibationError::InvalidState(
                format!("Task cannot be resumed: {:?}", task.status)
            ));
        }

        // Update status to queued
        self.update_task_status(task_id, TaskStatus::Queued).await?;

        // Try to start it
        self.try_start_next_download().await?;

        Ok(())
    }

    /// Cancel a download
    pub async fn cancel_download(&self, task_id: &str) -> Result<()> {
        // Stop if actively downloading
        let mut active = self.active_downloads.write().await;
        if let Some(download) = active.remove(task_id) {
            let _ = download.cancel_tx.send(());
            drop(active);
            let _ = tokio::time::timeout(
                tokio::time::Duration::from_secs(2),
                download.handle
            ).await;
        }

        // Update database
        self.update_task_status(task_id, TaskStatus::Cancelled).await?;

        // Delete partial file
        let task = self.get_task(task_id).await?;
        let _ = fs::remove_file(&task.download_path).await;

        Ok(())
    }

    /// Retry a failed download
    pub async fn retry_download(&self, task_id: &str) -> Result<()> {
        let task = self.get_task(task_id).await?;

        if task.status != TaskStatus::Failed {
            return Err(LibationError::InvalidState(
                format!("Can only retry failed downloads")
            ));
        }

        // Reset task state
        sqlx::query(
            "UPDATE DownloadTasks SET status = ?, retry_count = retry_count + 1, error = NULL WHERE task_id = ?"
        )
        .bind(TaskStatus::Queued.as_str())
        .bind(task_id)
        .execute(&*self.pool)
        .await?;

        self.try_start_next_download().await?;

        Ok(())
    }

    /// Register a progress callback for a task
    pub async fn register_progress_callback(&self, task_id: String, callback: ProgressCallback) {
        let mut callbacks = self.progress_callbacks.write().await;
        callbacks.insert(task_id, callback);
    }

    /// Resume all paused/queued downloads on app restart
    pub async fn resume_all_pending(&self) -> Result<()> {
        // Update any "downloading" tasks to "queued" (these were interrupted)
        sqlx::query("UPDATE DownloadTasks SET status = ? WHERE status = ?")
            .bind(TaskStatus::Queued.as_str())
            .bind(TaskStatus::Downloading.as_str())
            .execute(&*self.pool)
            .await?;

        // Start downloads up to concurrency limit
        for _ in 0..self.max_concurrent {
            if self.try_start_next_download().await.is_err() {
                break;
            }
        }

        Ok(())
    }

    // ========================================================================
    // Internal Methods
    // ========================================================================

    /// Try to start the next queued download if slots available
    async fn try_start_next_download(&self) -> Result<()> {
        // Check if we have capacity
        if self.get_active_count().await >= self.max_concurrent {
            return Ok(());
        }

        // Get next queued task
        let row = sqlx::query(
            "SELECT * FROM DownloadTasks WHERE status = ? ORDER BY created_at ASC LIMIT 1"
        )
        .bind(TaskStatus::Queued.as_str())
        .fetch_optional(&*self.pool)
        .await?;

        if let Some(row) = row {
            let task = self.row_to_task(row)?;
            self.start_download_worker(task).await;
        }

        Ok(())
    }

    /// Start a download worker for a task
    async fn start_download_worker(&self, task: DownloadTask) {
        let task_id = task.task_id.clone();
        let pool = Arc::clone(&self.pool);
        let semaphore = Arc::clone(&self.semaphore);
        let callbacks = Arc::clone(&self.progress_callbacks);
        let active = Arc::clone(&self.active_downloads);

        // Create cancellation channel
        let (cancel_tx, cancel_rx) = tokio::sync::oneshot::channel();

        // Spawn worker
        let handle = tokio::spawn(async move {
            // Acquire semaphore permit
            let _permit = semaphore.acquire().await.unwrap();

            // Run download
            let result = Self::download_worker(
                task.clone(),
                pool.clone(),
                callbacks.clone(),
                cancel_rx,
            ).await;

            // Handle result
            match result {
                Ok(()) => {
                    // Mark as completed
                    let _ = sqlx::query(
                        "UPDATE DownloadTasks SET status = ?, completed_at = ? WHERE task_id = ?"
                    )
                    .bind(TaskStatus::Completed.as_str())
                    .bind(chrono::Utc::now().to_rfc3339())
                    .bind(&task.task_id)
                    .execute(&*pool)
                    .await;

                    // Notify callback
                    if let Some(cb) = callbacks.read().await.get(&task.task_id) {
                        let mut completed_task = task.clone();
                        completed_task.status = TaskStatus::Completed;
                        cb(completed_task);
                    }
                }
                Err(e) => {
                    // Mark as failed
                    let _ = sqlx::query(
                        "UPDATE DownloadTasks SET status = ?, error = ? WHERE task_id = ?"
                    )
                    .bind(TaskStatus::Failed.as_str())
                    .bind(e.to_string())
                    .bind(&task.task_id)
                    .execute(&*pool)
                    .await;

                    // Notify callback
                    if let Some(cb) = callbacks.read().await.get(&task.task_id) {
                        let mut failed_task = task.clone();
                        failed_task.status = TaskStatus::Failed;
                        failed_task.error = Some(e.to_string());
                        cb(failed_task);
                    }
                }
            }

            // Remove from active
            active.write().await.remove(&task.task_id);

            // Try to start next download
            // (Note: This requires access to the manager, which we don't have here)
        });

        // Store active download
        let mut active_map = self.active_downloads.write().await;
        active_map.insert(task_id, ActiveDownload { handle, cancel_tx });
    }

    /// Download worker coroutine
    async fn download_worker(
        mut task: DownloadTask,
        pool: Arc<SqlitePool>,
        callbacks: Arc<RwLock<HashMap<String, ProgressCallback>>>,
        mut cancel_rx: tokio::sync::oneshot::Receiver<()>,
    ) -> Result<()> {
        // Update status to downloading
        sqlx::query(
            "UPDATE DownloadTasks SET status = ?, started_at = COALESCE(started_at, ?) WHERE task_id = ?"
        )
        .bind(TaskStatus::Downloading.as_str())
        .bind(chrono::Utc::now().to_rfc3339())
        .bind(&task.task_id)
        .execute(&*pool)
        .await?;

        task.status = TaskStatus::Downloading;

        // Create HTTP client
        let client = reqwest::Client::new();

        // Build request with headers
        let mut request = client.get(&task.download_url);
        for (key, value) in &task.request_headers {
            request = request.header(key, value);
        }

        // Add Range header for resumption
        if task.bytes_downloaded > 0 {
            request = request.header("Range", format!("bytes={}-", task.bytes_downloaded));
        }

        // Send request
        let response = request.send().await
            .map_err(|e| LibationError::NetworkError {
                message: format!("Request failed: {}", e),
                is_transient: true,
            })?;

        if !response.status().is_success() && response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
            return Err(LibationError::NetworkError {
                message: format!("HTTP {}", response.status()),
                is_transient: false,
            });
        }

        // Open file for writing (append mode if resuming)
        let mut file = if task.bytes_downloaded > 0 {
            fs::OpenOptions::new()
                .write(true)
                .append(true)
                .open(&task.download_path)
                .await?
        } else {
            fs::File::create(&task.download_path).await?
        };

        // Download stream
        let mut stream = response.bytes_stream();
        let mut last_update = tokio::time::Instant::now();

        while let Some(chunk_result) = tokio::select! {
            chunk = stream.next() => chunk,
            _ = &mut cancel_rx => {
                // Cancelled
                return Ok(());
            }
        } {
            let chunk = chunk_result.map_err(|e| LibationError::NetworkError {
                message: format!("Stream error: {}", e),
                is_transient: true,
            })?;

            // Write chunk
            file.write_all(&chunk).await?;
            task.bytes_downloaded += chunk.len() as u64;

            // Update database periodically (every 1 second)
            if last_update.elapsed() >= tokio::time::Duration::from_secs(1) {
                sqlx::query(
                    "UPDATE DownloadTasks SET bytes_downloaded = ? WHERE task_id = ?"
                )
                .bind(task.bytes_downloaded as i64)
                .bind(&task.task_id)
                .execute(&*pool)
                .await?;

                // Notify callback
                if let Some(cb) = callbacks.read().await.get(&task.task_id) {
                    cb(task.clone());
                }

                last_update = tokio::time::Instant::now();
            }
        }

        // Flush file
        file.flush().await?;

        // Final database update
        sqlx::query(
            "UPDATE DownloadTasks SET bytes_downloaded = ? WHERE task_id = ?"
        )
        .bind(task.bytes_downloaded as i64)
        .bind(&task.task_id)
        .execute(&*pool)
        .await?;

        Ok(())
    }

    /// Update task status
    async fn update_task_status(&self, task_id: &str, status: TaskStatus) -> Result<()> {
        sqlx::query("UPDATE DownloadTasks SET status = ? WHERE task_id = ?")
            .bind(status.as_str())
            .bind(task_id)
            .execute(&*self.pool)
            .await?;

        Ok(())
    }

    /// Convert database row to DownloadTask
    fn row_to_task(&self, row: sqlx::sqlite::SqliteRow) -> Result<DownloadTask> {
        let headers_json: String = row.try_get("request_headers")?;
        let request_headers: HashMap<String, String> = serde_json::from_str(&headers_json)
            .unwrap_or_default();

        let status_str: String = row.try_get("status")?;
        let status = TaskStatus::from_str(&status_str)?;

        Ok(DownloadTask {
            task_id: row.try_get("task_id")?,
            asin: row.try_get("asin")?,
            title: row.try_get("title")?,
            status,
            bytes_downloaded: row.try_get::<i64, _>("bytes_downloaded")? as u64,
            total_bytes: row.try_get::<i64, _>("total_bytes")? as u64,
            download_url: row.try_get("download_url")?,
            download_path: row.try_get("download_path")?,
            output_path: row.try_get("output_path")?,
            request_headers,
            error: row.try_get("error").ok(),
            retry_count: row.try_get("retry_count")?,
            created_at: row.try_get("created_at")?,
            started_at: row.try_get("started_at").ok(),
            completed_at: row.try_get("completed_at").ok(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::Database;

    #[tokio::test]
    async fn test_enqueue_download() {
        let db = Database::new_in_memory().await.unwrap();
        let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 3).await.unwrap();

        let task_id = manager.enqueue_download(
            "B001".to_string(),
            "Test Book".to_string(),
            "https://example.com/book.aax".to_string(),
            1000,
            "/tmp/book.aax".to_string(),
            "/tmp/book.m4b".to_string(),
            HashMap::new(),
        ).await.unwrap();

        let task = manager.get_task(&task_id).await.unwrap();
        assert_eq!(task.asin, "B001");
        assert_eq!(task.status, TaskStatus::Queued);
    }

    #[tokio::test]
    async fn test_list_tasks() {
        let db = Database::new_in_memory().await.unwrap();
        let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 3).await.unwrap();

        manager.enqueue_download(
            "B001".to_string(), "Book 1".to_string(), "https://example.com/1".to_string(),
            1000, "/tmp/1.aax".to_string(), "/tmp/1.m4b".to_string(), HashMap::new(),
        ).await.unwrap();

        manager.enqueue_download(
            "B002".to_string(), "Book 2".to_string(), "https://example.com/2".to_string(),
            2000, "/tmp/2.aax".to_string(), "/tmp/2.m4b".to_string(), HashMap::new(),
        ).await.unwrap();

        let tasks = manager.list_tasks(None).await.unwrap();
        assert_eq!(tasks.len(), 2);
    }

    #[tokio::test]
    async fn test_pause_download() {
        let db = Database::new_in_memory().await.unwrap();
        let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 3).await.unwrap();

        let task_id = manager.enqueue_download(
            "B001".to_string(), "Test Book".to_string(), "https://example.com/book.aax".to_string(),
            1000, "/tmp/book.aax".to_string(), "/tmp/book.m4b".to_string(), HashMap::new(),
        ).await.unwrap();

        manager.pause_download(&task_id).await.unwrap();

        let task = manager.get_task(&task_id).await.unwrap();
        assert_eq!(task.status, TaskStatus::Paused);
    }
}
