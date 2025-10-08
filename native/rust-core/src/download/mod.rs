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


//! Download management and streaming
//!
//! This module handles downloading audiobook files from Audible's CDN.
//!
//! # Reference C# Sources
//! - `AaxDecrypter/AudiobookDownloadBase.cs` - Base class for all download types
//! - `AaxDecrypter/NetworkFileStream.cs` - HTTP streaming with resume support
//! - `AaxDecrypter/NetworkFileStreamPersister.cs` - Persistent download state
//! - `FileLiberator/DownloadDecryptBook.cs` - High-level download orchestration
//! - `FileLiberator/DownloadOptions.cs` - Download configuration

pub mod manager;
pub mod stream;
pub mod progress;

// Re-export commonly used types
pub use manager::DownloadManager;
pub use progress::DownloadProgress;
