#!/usr/bin/env bash

set -e

echo "🔧 Running custom prebuild with FFmpeg integration..."

# Run standard expo prebuild first
echo "📱 Running expo prebuild..."
# EAS passes --platform argument, forward it to expo prebuild
npx expo prebuild --clean "$@"

# Integrate FFmpeg-Kit into android/app/libs/
if [ -d "android" ]; then
  echo "📦 Integrating FFmpeg-Kit..."

  # Create libs directory if needed
  mkdir -p android/app/libs

  # Copy FFmpeg-Kit AAR from build-assets
  if [ -f "build-assets/ffmpeg-kit.aar" ]; then
    cp build-assets/ffmpeg-kit.aar android/app/libs/ffmpeg-kit.aar
    echo "✅ FFmpeg-Kit integrated successfully ($(du -h build-assets/ffmpeg-kit.aar | cut -f1))"
  else
    echo "❌ ERROR: build-assets/ffmpeg-kit.aar not found!"
    exit 1
  fi
else
  echo "❌ ERROR: Android directory not found after prebuild!"
  exit 1
fi

echo "✅ Custom prebuild complete"
