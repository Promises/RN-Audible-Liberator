import { requireNativeModule } from 'expo-modules-core';

let ExpoRustBridge: any;

try {
  ExpoRustBridge = requireNativeModule('ExpoRustBridge');
} catch (e) {
  console.warn('Native Rust module not available, using fallback:', e);
  ExpoRustBridge = null;
}

export function logFromRust(message: string): string {
  if (ExpoRustBridge && ExpoRustBridge.logFromRust) {
    try {
      return ExpoRustBridge.logFromRust(message);
    } catch (e) {
      console.error('Error calling native Rust module:', e);
    }
  }

  // Fallback for development
  const fallbackMessage = `[Fallback] Rust native module says: ${message}`;
  console.log(fallbackMessage);
  return fallbackMessage;
}
