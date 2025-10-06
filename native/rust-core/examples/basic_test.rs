/// Basic test of Rust core functionality
/// Run with: cargo run --example basic_test

fn main() {
    println!("=== RN Audible Rust Core - Basic Test ===\n");

    // Test basic function
    let message = rust_core::log_from_rust("Testing from example".to_string());
    println!("✓ Basic function test passed");
    println!("  Result: {}\n", message);

    println!("=== All basic tests passed! ===");
}
