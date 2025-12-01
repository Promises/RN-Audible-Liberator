// LibriSync - Audible Library Sync for Mobile
// Copyright (C) 2025 Henning Berge
//
// This program is a Rust port of Libation (https://github.com/rmcrackan/Libation)
// Original work Copyright (C) Libation contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.


//! Library scanner for syncing filesystem with database
//!
//! Scans download directory for audio files, extracts metadata (ASIN),
//! and updates the database with file paths.

use crate::audio::metadata::MetadataEditor;
use crate::error::{LibationError, Result};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
use std::path::{Path, PathBuf};
use tokio::fs;

/// Scan results
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanResults {
    /// Number of audio files found
    pub files_found: usize,
    /// Number of books matched to database (by ASIN)
    pub books_matched: usize,
    /// Number of books updated in database with file paths
    pub books_updated: usize,
    /// Number of books in database marked as missing (file not found)
    pub books_missing: usize,
    /// Number of audio files with no matching ASIN
    pub files_unmatched: usize,
}

/// Library scanner
pub struct LibraryScanner {
    pool: SqlitePool,
}

impl LibraryScanner {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Scan directory and update database
    ///
    /// 1. Recursively scan directory for audio files (.m4b, .mp3, .m4a, .aac)
    /// 2. Extract ASIN from metadata tags
    /// 3. Update database: set file_path for books with matching ASIN
    /// 4. Mark books in database without file_path as missing
    ///
    /// Returns scan statistics
    pub async fn scan_and_update(&self, directory: &Path) -> Result<ScanResults> {
        if !directory.exists() {
            return Err(LibationError::InvalidPath(format!(
                "Directory does not exist: {}",
                directory.display()
            )));
        }

        if !directory.is_dir() {
            return Err(LibationError::InvalidPath(format!(
                "Path is not a directory: {}",
                directory.display()
            )));
        }

        let mut results = ScanResults {
            files_found: 0,
            books_matched: 0,
            books_updated: 0,
            books_missing: 0,
            files_unmatched: 0,
        };

        // First, clear all file paths (we'll rebuild them)
        sqlx::query("UPDATE UserDefinedItems SET file_path = NULL")
            .execute(&self.pool)
            .await?;

        // Scan directory recursively
        self.scan_recursive(directory, &mut results).await?;

        // Count books still without file paths (missing from filesystem)
        let missing_count: i64 = sqlx::query_scalar(
            r#"
            SELECT COUNT(*)
            FROM UserDefinedItems
            WHERE file_path IS NULL
            "#,
        )
        .fetch_one(&self.pool)
        .await?;
        results.books_missing = missing_count as usize;

        Ok(results)
    }

    /// Recursively scan directory
    fn scan_recursive<'a>(
        &'a self,
        dir: &'a Path,
        results: &'a mut ScanResults,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<()>> + 'a>> {
        Box::pin(async move {
            let mut entries = fs::read_dir(dir).await.map_err(|e| {
                LibationError::FileIoError(format!(
                    "Failed to read directory {}: {}",
                    dir.display(),
                    e
                ))
            })?;

            while let Some(entry) = entries.next_entry().await.map_err(|e| {
                LibationError::FileIoError(format!(
                    "Failed to read directory entry in {}: {}",
                    dir.display(),
                    e
                ))
            })? {
                let path = entry.path();

                if path.is_dir() {
                    // Recurse into subdirectories
                    self.scan_recursive(&path, results).await?;
                } else if self.is_audio_file(&path) {
                    results.files_found += 1;
                    self.process_audio_file(&path, results).await?;
                }
            }

            Ok(())
        })
    }

    /// Check if file is an audio file
    fn is_audio_file(&self, path: &Path) -> bool {
        if let Some(ext) = path.extension() {
            matches!(
                ext.to_str().map(|s| s.to_lowercase()).as_deref(),
                Some("m4b") | Some("mp3") | Some("m4a") | Some("aac")
            )
        } else {
            false
        }
    }

    /// Process a single audio file
    async fn process_audio_file(&self, path: &Path, results: &mut ScanResults) -> Result<()> {
        // Extract metadata (this might fail if FFmpeg not available)
        let metadata = match MetadataEditor::extract_metadata(path).await {
            Ok(meta) => meta,
            Err(_) => {
                // Can't extract metadata, skip this file
                results.files_unmatched += 1;
                return Ok(());
            }
        };

        // Check if we have an ASIN
        let asin = match metadata.asin {
            Some(asin) => asin,
            None => {
                // No ASIN in metadata, can't match to database
                results.files_unmatched += 1;
                return Ok(());
            }
        };

        // Look up book in database by ASIN
        let book_id: Option<i64> = sqlx::query_scalar(
            "SELECT book_id FROM Books WHERE audible_product_id = ?",
        )
        .bind(&asin)
        .fetch_optional(&self.pool)
        .await?;

        if let Some(book_id) = book_id {
            // Found matching book in database
            results.books_matched += 1;

            // Update file_path in UserDefinedItems
            let path_str = path.to_string_lossy().to_string();
            let rows_affected = sqlx::query(
                "UPDATE UserDefinedItems SET file_path = ? WHERE book_id = ?",
            )
            .bind(&path_str)
            .bind(book_id)
            .execute(&self.pool)
            .await?
            .rows_affected();

            if rows_affected > 0 {
                results.books_updated += 1;
            }
        } else {
            // ASIN not found in database
            results.files_unmatched += 1;
        }

        Ok(())
    }

    /// Check if a book exists in the download directory (by ASIN)
    ///
    /// Returns the file path if found
    pub async fn find_book_by_asin(&self, asin: &str) -> Result<Option<String>> {
        let file_path: Option<String> = sqlx::query_scalar(
            r#"
            SELECT file_path
            FROM UserDefinedItems
            WHERE book_id = (SELECT book_id FROM Books WHERE audible_product_id = ?)
            AND file_path IS NOT NULL
            "#,
        )
        .bind(asin)
        .fetch_optional(&self.pool)
        .await?;

        Ok(file_path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::Database;
    use tempfile::TempDir;

    #[tokio::test]
    async fn test_scan_empty_directory() {
        let temp_dir = TempDir::new().unwrap();
        let db = Database::open_in_memory().await.unwrap();
        let scanner = LibraryScanner::new(db.pool().clone());

        let results = scanner.scan_and_update(temp_dir.path()).await.unwrap();

        assert_eq!(results.files_found, 0);
        assert_eq!(results.books_matched, 0);
        assert_eq!(results.books_updated, 0);
    }
}
