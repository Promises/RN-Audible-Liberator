// RN Audible - React Native Audible Client
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


//! File management and path utilities
//!
//! This module handles file operations, path generation, and naming templates.
//!
//! # Reference C# Sources
//! - `FileManager/` - File utilities and operations
//! - `LibationFileManager/` - Libation-specific file operations
//! - `FileManager/NamingTemplate/` - Template system for file naming

pub mod manager;
pub mod paths;

// Re-export commonly used types
pub use manager::FileManager;
pub use paths::PathBuilder;
