uniffi::setup_scaffolding!();

// JNI bridge for Android
#[cfg(target_os = "android")]
mod jni_bridge;

#[uniffi::export]
pub fn log_from_rust(message: String) -> String {
    let log_message = format!("Rust native module says: {message}");
    println!("{log_message}");
    log_message
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_log_from_rust() {
        let result = log_from_rust("Hello".to_string());
        assert!(result.contains("Rust native module says: Hello"));
    }
}
