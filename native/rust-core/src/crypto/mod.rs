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


//! Cryptography and DRM removal
//!
//! This module handles decryption of Audible's DRM-protected audio formats.
//! It ports functionality from Libation's AaxDecrypter project.
//!
//! # Reference C# Sources
//! - `AaxDecrypter/` - Main decryption logic for AAX and AAXC formats
//! - `AudibleUtilities/Widevine/` - Widevine CDM implementation for AAXC
//!
//! # DRM Formats
//! - **AAX** (legacy): AES encryption with activation bytes
//! - **AAXC** (current): Widevine DRM with chunked MPEG-DASH delivery
//! - **Unencrypted**: Direct MP3/M4B for podcasts

pub mod activation;
pub mod aax;
pub mod aaxc;
pub mod widevine;

// Re-export commonly used types from activation module
pub use activation::{
    ActivationBytes,
    format_activation_bytes,
    parse_activation_bytes,
    validate_activation_bytes,
};

// Re-export commonly used types from AAX module
pub use aax::{
    AaxDecrypter,
    is_aax_file,
    verify_activation_bytes,
};

// Re-export AAXC decrypter (placeholder for now)
pub use aaxc::AaxcDecrypter;
