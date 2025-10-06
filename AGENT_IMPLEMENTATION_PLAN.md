# Agent Implementation Plan - Phase 1: Infrastructure

This document outlines how to use the general-purpose agent to implement Phase 1 (Core Infrastructure) of the Libation port.

## Overview

The agent will help with:
1. Setting up the Rust crate structure
2. Implementing error types
3. Creating HTTP client with retry logic
4. Setting up the database layer
5. Writing unit tests for each component

---

## Agent Task Breakdown

### Task 1: Setup Rust Crate Structure

**Agent Prompt:**
```
Set up the Rust crate structure for the RN Audible project in native/rust-core/.

Create the following directory structure:
- src/api/ (mod.rs, auth.rs, client.rs, library.rs, content.rs, license.rs)
- src/crypto/ (mod.rs, activation.rs, aax.rs, aaxc.rs, widevine.rs)
- src/download/ (mod.rs, manager.rs, stream.rs, progress.rs)
- src/audio/ (mod.rs, decoder.rs, converter.rs, metadata.rs)
- src/storage/ (mod.rs, database.rs, models.rs, migrations.rs, queries.rs)
- src/file/ (mod.rs, manager.rs, paths.rs)
- src/error.rs
- src/lib.rs (update with new modules)

Update Cargo.toml with the dependencies listed in LIBATION_PORT_PLAN.md Phase 1.

Create placeholder mod.rs files for each module with TODO comments.

Do not modify the existing jni_bridge.rs or the log_from_rust function.
```

**Expected Output:**
- Complete directory structure
- Updated Cargo.toml with Phase 1 dependencies
- Module declarations in lib.rs
- Placeholder files with module structure

**Validation:**
```bash
cargo check
```

---

### Task 2: Implement Error Types

**Agent Prompt:**
```
Implement comprehensive error types in native/rust-core/src/error.rs for the RN Audible project.

Requirements:
1. Use thiserror crate for error derivation
2. Create error enums for:
   - ApiError (HTTP status codes, network errors, auth failures)
   - CryptoError (decryption failures, key errors, activation bytes errors)
   - StorageError (database errors, file system errors)
   - DownloadError (network interruptions, resume failures)
   - AudioError (conversion failures, metadata errors)

3. Each error should:
   - Have descriptive variants
   - Include context (file paths, ASINs, status codes where applicable)
   - Implement Display with user-friendly messages
   - Support conversion with #[from] for underlying errors

4. Create a Result type alias: `pub type Result<T> = std::result::Result<T, Error>;`

5. Write unit tests for:
   - Error construction
   - Error message formatting
   - Error conversion chains
   - Serialization (for passing errors to JS)

Reference the error handling patterns from anyhow and thiserror documentation.
```

**Expected Output:**
- `src/error.rs` with comprehensive error types
- Unit tests in the same file
- Clear documentation comments

**Validation:**
```bash
cargo test error
cargo doc --no-deps --open
```

---

### Task 3: Implement HTTP Client

**Agent Prompt:**
```
Implement an async HTTP client in native/rust-core/src/api/client.rs for communicating with the Audible API.

Requirements:
1. Use reqwest crate with cookie jar support
2. Create an ApiClient struct with:
   - Base URL configuration (https://api.audible.com)
   - Cookie jar for session management
   - Custom User-Agent header
   - Timeout configuration (30s default)
   - Retry logic with exponential backoff (3 retries max)

3. Implement methods:
   - `new() -> Self` - constructor
   - `get(url, params) -> Result<Response>` - GET requests
   - `post(url, body) -> Result<Response>` - POST requests with JSON
   - `download_stream(url) -> Result<Stream>` - streaming downloads

4. Features:
   - Automatic retry on 5xx errors and network failures
   - Progress tracking callbacks (for downloads)
   - Custom headers per-request
   - JSON deserialization helpers

5. Write unit tests using mockito:
   - Successful requests
   - Retry logic (mock 500 → 500 → 200)
   - Timeout handling
   - Cookie persistence across requests

Reference the Audible API client patterns from Libation/Source/AudibleUtilities/ApiExtended.cs
```

**Expected Output:**
- `src/api/client.rs` with ApiClient implementation
- `src/api/mod.rs` with module exports
- Unit tests with mocked HTTP responses
- Documentation with usage examples

**Validation:**
```bash
cargo test api::client
```

---

### Task 4: Implement Database Layer - Models

**Agent Prompt:**
```
Implement database models in native/rust-core/src/storage/models.rs based on the Libation Entity Framework schema.

Requirements:
1. Reference the C# models from references/Libation/Source/DataLayer/EfClasses/
2. Create Rust structs for:
   - Book (ASIN, title, authors, narrators, runtime, description, etc.)
   - LibraryBook (user's ownership, account, locale)
   - UserDefinedItem (tags, download status, last downloaded)
   - Series (name, ASIN)
   - Contributor (name, ASIN, role)
   - Category (name, hierarchy)

3. Each struct should:
   - Use serde for serialization
   - Have appropriate derives (Debug, Clone, PartialEq)
   - Include sqlx attributes for database mapping
   - Have builder methods for construction
   - Include validation logic

4. Create enums for:
   - LiberatedStatus (NotLiberated, Liberated, Error)
   - AudioFormat (AAX, AAXC, M4B, MP3)
   - ContributorRole (Author, Narrator)

5. Add documentation comments with examples

Port the exact field names and types from the C# models to maintain compatibility with existing Libation databases if possible.
```

**Expected Output:**
- `src/storage/models.rs` with all entity models
- Enums for status and format types
- serde and sqlx derives
- Documentation

**Validation:**
```bash
cargo check
cargo doc --no-deps --open
```

---

### Task 5: Implement Database Layer - Schema & Migrations

**Agent Prompt:**
```
Implement the SQLite database schema and migrations in native/rust-core/src/storage/ for the RN Audible project.

Requirements:
1. Reference the Libation database schema from references/Libation/Source/DataLayer/LibationContext.cs

2. In src/storage/database.rs:
   - Create Database struct with SqlitePool
   - Implement `Database::new(path)` to create/open database
   - Implement connection pooling
   - Add migration runner

3. In src/storage/migrations.rs:
   - Create SQL migration files (or embedded SQL) for:
     - Books table (ASIN, title, authors JSON, narrators JSON, etc.)
     - LibraryBooks table (foreign key to Books, account, locale)
     - UserDefinedItems table (tags, download status)
     - Series table
     - Contributors table
     - Categories table
     - Junction tables (BookContributors, BookCategories, SeriesBooks)
   - Implement versioned migrations
   - Add rollback support

4. Use sqlx macros for compile-time query checking

5. Write integration tests:
   - Create database in temp directory
   - Run migrations
   - Verify schema
   - Test foreign key constraints

Reference:
- Libation/Source/DataLayer/Configurations/ for table configurations
- Libation/Source/DataLayer/Migrations/ for migration history
```

**Expected Output:**
- `src/storage/database.rs` with Database struct
- `src/storage/migrations.rs` with SQL migrations
- Integration tests that create and migrate database

**Validation:**
```bash
cargo test storage::database
cargo test storage::migrations
```

---

### Task 6: Implement Database Layer - Queries

**Agent Prompt:**
```
Implement database query helpers in native/rust-core/src/storage/queries.rs for the RN Audible project.

Requirements:
1. Create query functions for common operations:

Books:
- `insert_book(db, book) -> Result<()>`
- `get_book_by_asin(db, asin) -> Result<Book>`
- `get_all_books(db) -> Result<Vec<Book>>`
- `update_book(db, book) -> Result<()>`
- `delete_book(db, asin) -> Result<()>`
- `search_books(db, query) -> Result<Vec<Book>>`

LibraryBooks:
- `insert_library_book(db, library_book) -> Result<()>`
- `get_library_books_for_account(db, account) -> Result<Vec<LibraryBook>>`
- `update_download_status(db, asin, status) -> Result<()>`

Series:
- `get_books_in_series(db, series_asin) -> Result<Vec<Book>>`

Complex queries:
- `get_books_by_author(db, author_name) -> Result<Vec<Book>>`
- `get_books_by_narrator(db, narrator_name) -> Result<Vec<Book>>`
- `get_books_with_status(db, status) -> Result<Vec<Book>>`

2. Use sqlx query macros for type safety
3. Implement pagination for large result sets
4. Add transaction support for multi-step operations

5. Write unit tests with in-memory database:
   - Insert and retrieve books
   - Update operations
   - Complex queries with joins
   - Transaction rollback

Reference query patterns from references/Libation/Source/DataLayer/QueryObjects/
```

**Expected Output:**
- `src/storage/queries.rs` with CRUD and search functions
- Unit tests with test fixtures
- Documentation with usage examples

**Validation:**
```bash
cargo test storage::queries
```

---

### Task 7: Integrate Infrastructure Components

**Agent Prompt:**
```
Update native/rust-core/src/lib.rs to integrate the new infrastructure components (error types, HTTP client, database) and create a unified API surface.

Requirements:
1. Export public APIs:
   - Error types
   - ApiClient
   - Database
   - Models

2. Create high-level initialization function:
   - `pub fn initialize(config: Config) -> Result<AppContext>`
   - AppContext should hold: database connection, API client, config

3. Update existing log_from_rust function to use new error types

4. Add new JNI bridge functions for testing infrastructure:
   - `test_database_connection() -> Result<String>`
   - `test_api_connection() -> Result<String>`

5. Write integration tests in tests/integration/infrastructure.rs:
   - Initialize app context
   - Verify database accessible
   - Verify API client configured

6. Update documentation in lib.rs with architecture overview
```

**Expected Output:**
- Updated lib.rs with public API surface
- AppContext struct for dependency injection
- Integration test showing all components working together

**Validation:**
```bash
cargo test
cargo doc --no-deps --open
npm run build:rust:android
```

---

## Agent Execution Plan

### Week 1: Foundation

**Day 1-2: Structure & Errors**
1. Run Task 1 (agent: setup crate structure)
2. Run Task 2 (agent: implement error types)
3. Manual review and adjust
4. Commit: "feat: add Rust project structure and error types"

**Day 3-4: HTTP Client**
1. Run Task 3 (agent: implement HTTP client)
2. Manual testing with httpbin.org
3. Commit: "feat: add HTTP client with retry logic"

**Day 5: Database Models**
1. Run Task 4 (agent: implement database models)
2. Compare with Libation C# models
3. Commit: "feat: add database models"

### Week 2: Database & Integration

**Day 1-2: Database Schema**
1. Run Task 5 (agent: implement database and migrations)
2. Test migrations manually
3. Commit: "feat: add SQLite database with migrations"

**Day 3-4: Database Queries**
1. Run Task 6 (agent: implement query helpers)
2. Run tests and verify coverage
3. Commit: "feat: add database query layer"

**Day 5: Integration**
1. Run Task 7 (agent: integrate components)
2. Run full test suite
3. Build for Android and test on device
4. Commit: "feat: integrate infrastructure components"

---

## Manual Review Checklist

After each agent task, review:

- [ ] Code compiles (`cargo check`)
- [ ] Tests pass (`cargo test`)
- [ ] Documentation builds (`cargo doc`)
- [ ] No compiler warnings
- [ ] Code follows Rust best practices
- [ ] Error messages are user-friendly
- [ ] No unwrap() or panic!() in production code
- [ ] Proper async/await usage
- [ ] Database migrations are reversible
- [ ] Sensitive data not logged

---

## Agent Invocation Examples

### Using the Task Tool

```typescript
// Task 1: Setup structure
await task({
  description: "Setup Rust crate structure",
  prompt: `[Task 1 prompt from above]`,
  subagent_type: "general-purpose"
});

// Task 2: Error types
await task({
  description: "Implement error types",
  prompt: `[Task 2 prompt from above]`,
  subagent_type: "general-purpose"
});

// etc...
```

---

## Success Criteria for Phase 1

Phase 1 is complete when:

- [ ] All Cargo.toml dependencies compile
- [ ] Error types are comprehensive and tested
- [ ] HTTP client can make requests (tested with mocks)
- [ ] Database schema matches Libation
- [ ] Database queries work (tested)
- [ ] All unit tests pass (>80% coverage)
- [ ] Integration test passes
- [ ] Rust library builds for Android
- [ ] Documentation is complete
- [ ] Code review approved

**Estimated Time:** 2 weeks with agent assistance (vs 3-4 weeks manual)

---

## Troubleshooting

### Agent doesn't follow instructions
- Break task into smaller sub-tasks
- Provide more specific examples
- Reference specific files from Libation

### Tests are failing
- Review test mocks and fixtures
- Check async/await usage
- Verify database migrations

### Android build fails
- Check JNI function signatures
- Verify no `std::io` in mobile targets
- Use `#[cfg(target_os = "android")]` conditionally

---

## Next Phase Preview

After Phase 1 is complete, we'll use agents for Phase 2 (Authentication & API):
- OAuth 2.0 flow implementation
- Device registration
- Token management
- Library sync

The foundation from Phase 1 will make Phase 2 much faster to implement.
