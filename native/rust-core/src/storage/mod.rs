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


//! Database storage and models
//!
//! This module handles all database operations using SQLite.
//! It ports Libation's Entity Framework data layer to Rust with sqlx.
//!
//! # Reference C# Sources
//! - `DataLayer/EfClasses/` - Entity models (Book, LibraryBook, Series, etc.)
//! - `DataLayer/Configurations/` - EF Core configurations (table schema)
//! - `DataLayer/Migrations/` - Database migrations
//! - `DataLayer/LibationContext.cs` - DbContext (database access)
//!
//! # Database Schema
//! - Books: Core book metadata (title, ASIN, runtime, etc.)
//! - LibraryBooks: User ownership/library membership
//! - Contributors: Authors and narrators
//! - Series: Book series information
//! - Categories: Genres and tags
//! - Many-to-many junction tables for relationships
//!
//! # Usage Example
//! ```no_run
//! use rust_core::storage::{Database, queries, models::NewBook};
//!
//! # async fn example() -> Result<(), Box<dyn std::error::Error>> {
//! // Create database
//! let db = Database::new("./my_library.db").await?;
//!
//! // Insert a book
//! let new_book = NewBook::new(
//!     "B012345678".to_string(),
//!     "The Hobbit".to_string(),
//!     "us".to_string(),
//! );
//! let book_id = queries::insert_book(db.pool(), &new_book).await?;
//!
//! // Find book by ASIN
//! let book = queries::find_book_by_asin(db.pool(), "B012345678").await?;
//! # Ok(())
//! # }
//! ```

pub mod accounts;
pub mod database;
pub mod migrations;
pub mod models;
pub mod queries;

// Re-export commonly used types
pub use database::{Database, DatabaseStats};
pub use models::{
    AudioFormat, Book, BookCategory, BookContributor, Category, CategoryLadder, Codec,
    ContentType, Contributor, LiberatedStatus, LibraryBook, NewBook, NewCategory,
    NewCategoryLadder, NewContributor, NewLibraryBook, NewSeries, NewUserDefinedItem, Rating,
    Role, Series, SeriesBook, Supplement, UserDefinedItem,
};
