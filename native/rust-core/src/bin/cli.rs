use clap::{Parser, Subcommand};
use rust_core::log_from_rust;

#[derive(Parser)]
#[command(name = "rn-audible-cli")]
#[command(about = "RN Audible CLI - Desktop testing tool", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Test the basic Rust bridge
    Test {
        /// Message to send to Rust
        #[arg(short, long, default_value = "Hello from CLI!")]
        message: String,
    },
    /// Test authentication (placeholder)
    Auth {
        /// Email address
        #[arg(short, long)]
        email: String,
        /// Password
        #[arg(short, long)]
        password: String,
    },
    /// Test library sync (placeholder)
    Sync,
    /// Test download (placeholder)
    Download {
        /// Book ASIN
        asin: String,
    },
}

fn main() {
    let cli = Cli::parse();

    match cli.command {
        Commands::Test { message } => {
            println!("Testing Rust bridge...");
            let result = log_from_rust(message);
            println!("Result: {}", result);
        }
        Commands::Auth { email, password } => {
            println!("Testing authentication...");
            println!("Email: {}", email);
            println!("Password: {}", "*".repeat(password.len()));
            println!("⚠️  Authentication not yet implemented");
        }
        Commands::Sync => {
            println!("Testing library sync...");
            println!("⚠️  Library sync not yet implemented");
        }
        Commands::Download { asin } => {
            println!("Testing download for ASIN: {}", asin);
            println!("⚠️  Download not yet implemented");
        }
    }
}
