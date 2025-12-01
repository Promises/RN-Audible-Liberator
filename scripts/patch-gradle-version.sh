#!/bin/bash
# Patch android/app/build.gradle to add dynamic version reading
# This script runs after expo prebuild in Docker builds

set -e

GRADLE_FILE="android/app/build.gradle"

if [ ! -f "$GRADLE_FILE" ]; then
    echo "Error: $GRADLE_FILE not found"
    exit 1
fi

echo "Patching $GRADLE_FILE with dynamic version code and name..."

# Create the patch to insert after keystore properties
cat > /tmp/version-patch.gradle << 'EOF'

/**
 * Get version from app.config.js (preferred) or package.json (fallback)
 * Expo uses app.config.js as the primary configuration source
 */
def getVersionName = { ->
    // Try reading from app.config.js first (Expo's primary config)
    try {
        def appConfigFile = new File(projectRoot, 'app.config.js')
        if (appConfigFile.exists()) {
            def appConfigText = appConfigFile.text
            // Extract version using regex: version: "x.x.x"
            def matcher = appConfigText =~ /version:\s*["']([^"']+)["']/
            if (matcher.find()) {
                def version = matcher.group(1)
                logger.quiet("Using version from app.config.js: ${version}")
                return version
            }
        }
    } catch (Exception e) {
        logger.warn("Could not parse app.config.js: ${e.message}")
    }

    // Fallback to package.json
    try {
        def packageJson = new groovy.json.JsonSlurper().parseText(
            new File(projectRoot, 'package.json').text
        )
        logger.quiet("Using version from package.json: ${packageJson.version}")
        return packageJson.version
    } catch (Exception e) {
        logger.warn("Could not read version from package.json: ${e.message}")
        return "0.0.3"
    }
}

/**
 * Generate versionCode from unix timestamp / 10
 * This ensures each build has a unique, incrementing version code
 */
def getVersionCode = { ->
    return (int) (System.currentTimeMillis() / 10000)
}
EOF

# Find the line with keystoreProperties and insert after it
awk '/^def keystoreProperties = new Properties\(\)/ {
    print
    getline
    print
    while ((getline < "/tmp/version-patch.gradle") > 0) print
    close("/tmp/version-patch.gradle")
    next
}
{ print }' "$GRADLE_FILE" > "${GRADLE_FILE}.tmp"

mv "${GRADLE_FILE}.tmp" "$GRADLE_FILE"

# Replace hardcoded versionCode and versionName
sed -i.bak 's/versionCode [0-9]*/versionCode getVersionCode()/g' "$GRADLE_FILE"
sed -i.bak 's/versionName "[^"]*"/versionName getVersionName()/g' "$GRADLE_FILE"
rm -f "${GRADLE_FILE}.bak"

# Add version logging before buildTypes closing brace
awk '/^    buildTypes \{/,/^    \}/ {
    if (/^    \}/ && !printed) {
        print ""
        print "    // Print version info before building"
        print "    applicationVariants.all { variant ->"
        print "        variant.assembleProvider.get().doFirst {"
        print "            println \"==========================================\""
        print "            println \"Building LibriSync ${variant.buildType.name}\""
        print "            println \"Version Name: ${variant.versionName}\""
        print "            println \"Version Code: ${variant.versionCode}\""
        print "            println \"==========================================\""
        print "        }"
        print "    }"
        printed = 1
    }
    print
    next
}
{ print }' "$GRADLE_FILE" > "${GRADLE_FILE}.tmp"

mv "${GRADLE_FILE}.tmp" "$GRADLE_FILE"

echo "✓ Patched $GRADLE_FILE successfully"
