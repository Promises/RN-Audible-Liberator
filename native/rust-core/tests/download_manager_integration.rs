//! Integration test for PersistentDownloadManager
//!
//! Tests the complete download flow with a real Audible account and book.
//! Requires valid credentials in test_fixtures/registration_response.json

use rust_core::api::auth::Account;
use rust_core::api::client::AudibleClient;
use rust_core::api::content::DownloadQuality;
use rust_core::download::{PersistentDownloadManager, TaskStatus};
use rust_core::storage::Database;
use std::sync::Arc;
use std::time::Duration;

/// Test book: "A Mind of Her Own" by Jo Nesbo
const TEST_ASIN: &str = "B07NP9L44Y";

/// Load account from test fixture
fn load_test_account() -> Result<Account, Box<dyn std::error::Error>> {
    use rust_core::api::auth::{Identity, Locale, AccessToken};
    use rust_core::api::registration::RegistrationResponse;

    let fixture_path = concat!(env!("CARGO_MANIFEST_DIR"), "/test_fixtures/registration_response.json");
    let fixture_data = std::fs::read_to_string(fixture_path)?;
    let reg_response: RegistrationResponse = serde_json::from_str(&fixture_data)?;

    // Convert RegistrationResponse to Account
    let success = reg_response.response.success;
    let bearer = &success.tokens.bearer;
    let mac_dms = &success.tokens.mac_dms;

    let access_token = AccessToken {
        token: bearer.access_token.clone(),
        expires_at: chrono::Utc::now() + chrono::Duration::seconds(bearer.expires_in.parse::<i64>().unwrap_or(3600)),
    };

    let identity = Identity::new(
        access_token,
        bearer.refresh_token.clone(),
        mac_dms.device_private_key.clone(),
        mac_dms.adp_token.clone(),
        Locale::us(), // Default to US
    );

    let account = Account {
        account_id: success.extensions.device_info.device_serial_number.clone(),
        account_name: success.extensions.customer_info.name.clone(),
        library_scan: true,
        decrypt_key: String::new(),
        identity: Some(identity),
    };

    Ok(account)
}

#[tokio::test]
#[ignore] // Run with: cargo test --test download_manager_integration -- --ignored --nocapture
async fn test_download_book_with_manager() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n=== Testing PersistentDownloadManager with real book ===\n");

    // Load account
    println!("1. Loading account credentials...");
    let mut account = load_test_account()?;
    println!("   ✓ Loaded account: {}", account.account_name);

    // Always refresh token to ensure it's valid (fixtures may be old)
    println!("\n2. Refreshing access token...");
    if let Some(ref identity) = account.identity {
        let locale = identity.locale.clone();
        let refresh_token = identity.refresh_token.clone();
        let device_serial = account.account_id.clone();

        let new_tokens = rust_core::api::auth::refresh_access_token(
            &locale,
            &refresh_token,
            &device_serial,
        ).await?;

        println!("   ✓ Token refreshed");
        println!("   - Expires in: {} seconds", new_tokens.expires_in);

        // Update account with new tokens
        if let Some(ref mut id) = account.identity {
            id.access_token.token = new_tokens.access_token;
            if let Some(new_refresh) = new_tokens.refresh_token {
                id.refresh_token = new_refresh;
            }
            id.access_token.expires_at = chrono::Utc::now()
                .checked_add_signed(chrono::Duration::seconds(new_tokens.expires_in as i64))
                .unwrap();
        }
    }

    // Create client and get download license
    println!("\n3. Getting download license for {}...", TEST_ASIN);
    let client = AudibleClient::new(account.clone())?;
    let license = client.build_download_license(TEST_ASIN, DownloadQuality::High, false).await?;
    println!("   ✓ License obtained");
    println!("   - Download URL: {}", &license.download_url[..std::cmp::min(80, license.download_url.len())]);

    // Extract headers for download
    let mut request_headers = std::collections::HashMap::new();
    request_headers.insert("User-Agent".to_string(), "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0".to_string());

    // Create temporary database
    println!("\n4. Setting up download manager...");
    let temp_dir = tempfile::tempdir()?;
    let db_path = temp_dir.path().join("test_downloads.db");
    let db = Database::new(db_path.to_str().unwrap()).await?;
    println!("   ✓ Database created: {:?}", db_path);

    // Create download manager
    let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 3).await?;
    println!("   ✓ Download manager initialized");

    // Prepare download paths
    let download_dir = temp_dir.path().join("downloads");
    std::fs::create_dir_all(&download_dir)?;
    let encrypted_path = download_dir.join(format!("{}.aax", TEST_ASIN));
    let output_path = download_dir.join(format!("{}.m4b", TEST_ASIN));

    // Get expected file size from HTTP HEAD request
    println!("\n4. Getting file size...");
    let http_client = reqwest::Client::new();
    let head_response = http_client
        .head(&license.download_url)
        .header("User-Agent", "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0")
        .send()
        .await?;

    let total_bytes = head_response
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);

    println!("   ✓ File size: {} bytes ({:.2} MB)", total_bytes, total_bytes as f64 / 1024.0 / 1024.0);

    // Enqueue download
    println!("\n5. Setting up download manager...");
    let task_id = manager.enqueue_download(
        TEST_ASIN.to_string(),
        "A Mind of Her Own".to_string(),
        license.download_url.clone(),
        total_bytes,
        encrypted_path.to_str().unwrap().to_string(),
        output_path.to_str().unwrap().to_string(),
        request_headers,
    ).await?;
    println!("   ✓ Download enqueued: {}", task_id);

    // Monitor progress
    println!("\n6. Monitoring download progress...");
    let start = std::time::Instant::now();
    let mut last_progress = 0u64;
    let mut last_print = std::time::Instant::now();

    loop {
        tokio::time::sleep(Duration::from_millis(500)).await;

        let task = manager.get_task(&task_id).await?;

        // Print progress update every 2 seconds or on status change
        if last_print.elapsed() >= Duration::from_secs(2) || task.status != TaskStatus::Downloading {
            let percentage = task.progress_percentage();
            let speed = if task.bytes_downloaded > last_progress {
                let bytes_diff = task.bytes_downloaded - last_progress;
                let time_diff = last_print.elapsed().as_secs_f64();
                if time_diff > 0.0 {
                    bytes_diff as f64 / time_diff / 1024.0 / 1024.0 // MB/s
                } else {
                    0.0
                }
            } else {
                0.0
            };

            println!(
                "   [{:?}] {:.1}% ({} / {} bytes) - {:.2} MB/s",
                task.status,
                percentage,
                task.bytes_downloaded,
                task.total_bytes,
                speed
            );

            last_progress = task.bytes_downloaded;
            last_print = std::time::Instant::now();
        }

        match task.status {
            TaskStatus::Completed => {
                println!("\n✓ Download completed in {:?}!", start.elapsed());
                break;
            }
            TaskStatus::Failed => {
                return Err(format!("Download failed: {:?}", task.error).into());
            }
            TaskStatus::Cancelled => {
                return Err("Download was cancelled".into());
            }
            _ => {
                // Continue monitoring
            }
        }

        // Safety timeout (10 minutes)
        if start.elapsed() > Duration::from_secs(600) {
            return Err("Download timed out after 10 minutes".into());
        }
    }

    // Verify file exists
    println!("\n7. Verifying downloaded file...");
    let metadata = tokio::fs::metadata(&encrypted_path).await?;
    println!("   ✓ File exists: {:?}", encrypted_path);
    println!("   ✓ File size: {} bytes ({:.2} MB)", metadata.len(), metadata.len() as f64 / 1024.0 / 1024.0);

    // Verify file size matches expected
    if total_bytes > 0 {
        let size_diff = (metadata.len() as i64 - total_bytes as i64).abs();
        let size_diff_pct = (size_diff as f64 / total_bytes as f64) * 100.0;

        if size_diff_pct < 1.0 {
            println!("   ✓ File size matches expected ({:.2}% difference)", size_diff_pct);
        } else {
            println!("   ⚠ File size differs by {:.2}% from expected", size_diff_pct);
        }
    }

    println!("\n=== Test completed successfully! ===\n");
    Ok(())
}

#[tokio::test]
#[ignore]
async fn test_pause_resume_download() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n=== Testing Pause/Resume functionality ===\n");

    // Load account
    let account = load_test_account()?;
    println!("1. Loaded account: {}", account.account_name);

    // Get license
    println!("\n2. Getting download license...");
    let client = AudibleClient::new(account.clone())?;
    let license = client.build_download_license(TEST_ASIN, DownloadQuality::High, false).await?;
    println!("   ✓ License obtained");

    // Setup manager
    let temp_dir = tempfile::tempdir()?;
    let db_path = temp_dir.path().join("test_pause_resume.db");
    let db = Database::new(db_path.to_str().unwrap()).await?;
    let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 3).await?;

    // Prepare paths
    let download_dir = temp_dir.path().join("downloads");
    std::fs::create_dir_all(&download_dir)?;
    let encrypted_path = download_dir.join(format!("{}.aax", TEST_ASIN));
    let output_path = download_dir.join(format!("{}.m4b", TEST_ASIN));

    let mut request_headers = std::collections::HashMap::new();
    request_headers.insert("User-Agent".to_string(), "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0".to_string());

    // Get file size from HTTP HEAD
    let http_client = reqwest::Client::new();
    let head_response = http_client
        .head(&license.download_url)
        .header("User-Agent", "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0")
        .send()
        .await?;

    let total_bytes = head_response
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);

    // Enqueue download
    println!("\n3. Starting download...");
    let task_id = manager.enqueue_download(
        TEST_ASIN.to_string(),
        "Test Book".to_string(),
        license.download_url.clone(),
        total_bytes,
        encrypted_path.to_str().unwrap().to_string(),
        output_path.to_str().unwrap().to_string(),
        request_headers,
    ).await?;
    println!("   ✓ Download started: {}", task_id);

    // Wait for some progress (5 seconds)
    println!("\n4. Waiting for download to start...");
    tokio::time::sleep(Duration::from_secs(5)).await;

    let task_before_pause = manager.get_task(&task_id).await?;
    println!("   ✓ Progress: {:.1}% ({} bytes)",
        task_before_pause.progress_percentage(),
        task_before_pause.bytes_downloaded
    );

    // Pause download
    println!("\n5. Pausing download...");
    manager.pause_download(&task_id).await?;
    tokio::time::sleep(Duration::from_secs(1)).await;

    let task_paused = manager.get_task(&task_id).await?;
    assert_eq!(task_paused.status, TaskStatus::Paused);
    println!("   ✓ Download paused at {} bytes", task_paused.bytes_downloaded);

    // Resume download
    println!("\n6. Resuming download...");
    manager.resume_download(&task_id).await?;

    // Wait for resume to take effect
    tokio::time::sleep(Duration::from_secs(2)).await;

    let task_resumed = manager.get_task(&task_id).await?;
    println!("   ✓ Download resumed: {:?}", task_resumed.status);
    println!("   ✓ Current progress: {:.1}% ({} bytes)",
        task_resumed.progress_percentage(),
        task_resumed.bytes_downloaded
    );

    // Cancel the download (we don't need to complete it)
    println!("\n7. Cancelling download...");
    manager.cancel_download(&task_id).await?;
    tokio::time::sleep(Duration::from_secs(1)).await;

    let task_cancelled = manager.get_task(&task_id).await?;
    assert_eq!(task_cancelled.status, TaskStatus::Cancelled);
    println!("   ✓ Download cancelled");

    println!("\n=== Pause/Resume test completed! ===\n");
    Ok(())
}

#[tokio::test]
async fn test_download_manager_with_public_url() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n=== Testing PersistentDownloadManager with public file ===\n");

    // Create database
    println!("1. Setting up download manager...");
    let temp_dir = tempfile::tempdir()?;
    let db_path = temp_dir.path().join("test_public.db");
    let db = Database::new(db_path.to_str().unwrap()).await?;
    let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 2).await?;
    println!("   ✓ Download manager initialized");

    // Prepare download paths
    let download_dir = temp_dir.path().join("downloads");
    std::fs::create_dir_all(&download_dir)?;

    // Use a small public file for testing (100KB Lorem Ipsum text)
    let test_url = "https://www.gutenberg.org/cache/epub/10/pg10.txt"; // The King James Bible (5MB)
    let download_path = download_dir.join("test_file.txt");
    let output_path = download_dir.join("test_file_final.txt");

    let mut headers = std::collections::HashMap::new();
    headers.insert("User-Agent".to_string(), "rust-core-test/0.1".to_string());

    // Get file size
    println!("\n2. Getting file size...");
    let http_client = reqwest::Client::new();
    let head_response = http_client
        .head(test_url)
        .header("User-Agent", "rust-core-test/0.1")
        .send()
        .await?;

    let total_bytes = head_response
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);

    println!("   ✓ File size: {} bytes ({:.2} MB)", total_bytes, total_bytes as f64 / 1024.0 / 1024.0);

    // Enqueue download
    println!("\n3. Enqueueing download...");
    let task_id = manager.enqueue_download(
        "TEST001".to_string(),
        "Test File".to_string(),
        test_url.to_string(),
        total_bytes,
        download_path.to_str().unwrap().to_string(),
        output_path.to_str().unwrap().to_string(),
        headers,
    ).await?;
    println!("   ✓ Download enqueued: {}", task_id);

    // Monitor progress
    println!("\n4. Monitoring download progress...");
    let start = std::time::Instant::now();
    let mut last_progress = 0u64;
    let mut last_print = std::time::Instant::now();

    loop {
        tokio::time::sleep(Duration::from_millis(200)).await;

        let task = manager.get_task(&task_id).await?;

        // Print progress update
        if last_print.elapsed() >= Duration::from_secs(1) || task.status != TaskStatus::Downloading {
            let percentage = task.progress_percentage();
            let speed = if task.bytes_downloaded > last_progress {
                let bytes_diff = task.bytes_downloaded - last_progress;
                let time_diff = last_print.elapsed().as_secs_f64();
                if time_diff > 0.0 {
                    bytes_diff as f64 / time_diff / 1024.0 / 1024.0 // MB/s
                } else {
                    0.0
                }
            } else {
                0.0
            };

            println!(
                "   [{:?}] {:.1}% ({} / {} bytes) - {:.2} MB/s",
                task.status,
                percentage,
                task.bytes_downloaded,
                task.total_bytes,
                speed
            );

            last_progress = task.bytes_downloaded;
            last_print = std::time::Instant::now();
        }

        match task.status {
            TaskStatus::Completed => {
                println!("\n✓ Download completed in {:?}!", start.elapsed());
                break;
            }
            TaskStatus::Failed => {
                return Err(format!("Download failed: {:?}", task.error).into());
            }
            TaskStatus::Cancelled => {
                return Err("Download was cancelled".into());
            }
            _ => {
                // Continue monitoring
            }
        }

        // Safety timeout (2 minutes for public file)
        if start.elapsed() > Duration::from_secs(120) {
            return Err("Download timed out after 2 minutes".into());
        }
    }

    // Verify file exists
    println!("\n5. Verifying downloaded file...");
    let metadata = tokio::fs::metadata(&download_path).await?;
    println!("   ✓ File exists: {:?}", download_path);
    println!("   ✓ File size: {} bytes ({:.2} MB)", metadata.len(), metadata.len() as f64 / 1024.0 / 1024.0);

    // Verify file size matches expected
    if total_bytes > 0 {
        assert_eq!(metadata.len(), total_bytes, "File size mismatch");
        println!("   ✓ File size matches expected");
    }

    println!("\n=== Test completed successfully! ===\n");
    Ok(())
}

#[tokio::test]
#[ignore]
async fn test_list_downloads() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n=== Testing List Downloads functionality ===\n");

    let temp_dir = tempfile::tempdir()?;
    let db_path = temp_dir.path().join("test_list.db");
    let db = Database::new(db_path.to_str().unwrap()).await?;
    let manager = PersistentDownloadManager::new(Arc::new(db.pool().clone()), 3).await?;

    // Enqueue multiple fake downloads
    println!("1. Enqueueing test downloads...");
    let mut task_ids = Vec::new();
    let mut headers = std::collections::HashMap::new();
    headers.insert("User-Agent".to_string(), "Test".to_string());

    for i in 1..=5 {
        let task_id = manager.enqueue_download(
            format!("B00{}", i),
            format!("Test Book {}", i),
            format!("https://example.com/book{}.aax", i),
            1000000 * i as u64,
            format!("/tmp/book{}.aax", i),
            format!("/tmp/book{}.m4b", i),
            headers.clone(),
        ).await?;
        task_ids.push(task_id);
    }
    println!("   ✓ Enqueued 5 downloads");

    // List all tasks
    println!("\n2. Listing all tasks...");
    let all_tasks = manager.list_tasks(None).await?;
    println!("   ✓ Found {} tasks", all_tasks.len());
    assert_eq!(all_tasks.len(), 5);

    for (i, task) in all_tasks.iter().enumerate() {
        println!("   - Task {}: {} ({:?})", i + 1, task.title, task.status);
    }

    // List only queued tasks
    println!("\n3. Listing queued tasks...");
    let queued_tasks = manager.list_tasks(Some(TaskStatus::Queued)).await?;
    println!("   ✓ Found {} queued tasks", queued_tasks.len());

    println!("\n=== List test completed! ===\n");
    Ok(())
}
