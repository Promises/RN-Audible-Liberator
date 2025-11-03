const { withAppBuildGradle, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo config plugin to integrate FFmpeg-Kit with 16KB page alignment
 *
 * This plugin:
 * 1. Copies ffmpeg-kit.aar to android/app/libs/
 * 2. Adds gradle dependencies for FFmpeg-Kit
 *
 * The .aar file should be at: build-assets/ffmpeg-kit.aar
 */
const withFFmpegKit = (config) => {
  // Step 1: Add gradle dependencies
  config = withAppBuildGradle(config, (config) => {
    const buildGradle = config.modResults.contents;

    // Check if FFmpeg-Kit dependencies are already added
    if (buildGradle.includes('ffmpeg-kit.aar') || buildGradle.includes('smart-exception-java')) {
      console.log('FFmpeg-Kit dependencies already present in build.gradle');
      return config;
    }

    // Find the dependencies block and add FFmpeg-Kit
    const dependenciesBlockRegex = /dependencies\s*\{[\s\S]*?\n\}/;
    const match = buildGradle.match(dependenciesBlockRegex);

    if (!match) {
      console.error('Could not find dependencies block in build.gradle');
      return config;
    }

    const dependenciesBlock = match[0];

    // Add FFmpeg-Kit dependencies before the closing brace
    const updatedDependenciesBlock = dependenciesBlock.replace(
      /(\n\})/,
      `\n\n    // FFmpeg-Kit for audio conversion (16KB page aligned)
    implementation fileTree(dir: 'libs', include: ['*.aar'])
    implementation 'com.arthenica:smart-exception-java:0.1.1'$1`
    );

    config.modResults.contents = buildGradle.replace(
      dependenciesBlockRegex,
      updatedDependenciesBlock
    );

    console.log('✓ Added FFmpeg-Kit gradle dependencies');
    return config;
  });

  // Step 2: Copy .aar file to android/app/libs/
  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const sourceAar = path.join(projectRoot, 'build-assets', 'ffmpeg-kit.aar');
      const libsDir = path.join(projectRoot, 'android', 'app', 'libs');
      const destAar = path.join(libsDir, 'ffmpeg-kit.aar');

      // Check if source .aar exists
      if (!fs.existsSync(sourceAar)) {
        console.warn(`⚠️  Warning: FFmpeg-Kit .aar not found at: ${sourceAar}`);
        console.warn('   Run "npm run build:ffmpeg" to build it, or restore from backup');
        return config;
      }

      // Create libs directory if it doesn't exist
      if (!fs.existsSync(libsDir)) {
        fs.mkdirSync(libsDir, { recursive: true });
      }

      // Copy .aar file
      fs.copyFileSync(sourceAar, destAar);

      const stats = fs.statSync(destAar);
      const sizeInMB = (stats.size / (1024 * 1024)).toFixed(1);
      console.log(`✓ Copied ffmpeg-kit.aar to android/app/libs/ (${sizeInMB} MB)`);

      return config;
    },
  ]);

  return config;
};

module.exports = withFFmpegKit;
