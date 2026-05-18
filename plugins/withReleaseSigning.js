const { withAppBuildGradle } = require('expo/config-plugins');

/**
 * Expo config plugin that configures release signing from keystore.properties.
 * The Dockerfile writes keystore.properties during the build, and this plugin
 * ensures build.gradle reads it for the release signingConfig.
 */
module.exports = function withReleaseSigning(config) {
  return withAppBuildGradle(config, (config) => {
    let buildGradle = config.modResults.contents;

    // Add keystore.properties loading at the top of the android block
    const keystorePropsBlock = `
def keystorePropertiesFile = rootProject.file("keystore.properties")
def keystoreProperties = new Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(new FileInputStream(keystorePropertiesFile))
}
`;

    // Insert before signingConfigs
    if (!buildGradle.includes('keystorePropertiesFile')) {
      buildGradle = buildGradle.replace(
        'signingConfigs {',
        `${keystorePropsBlock}\n    signingConfigs {`
      );
    }

    // Add release signing config
    if (!buildGradle.includes("signingConfigs.release")) {
      // Add release config inside signingConfigs block
      buildGradle = buildGradle.replace(
        /signingConfigs\s*\{/,
        `signingConfigs {
        release {
            if (keystorePropertiesFile.exists()) {
                storeFile file(keystoreProperties['MYAPP_UPLOAD_STORE_FILE'])
                storePassword keystoreProperties['MYAPP_UPLOAD_STORE_PASSWORD']
                keyAlias keystoreProperties['MYAPP_UPLOAD_KEY_ALIAS']
                keyPassword keystoreProperties['MYAPP_UPLOAD_KEY_PASSWORD']
            }
        }`
      );

      // Switch release buildType to use release signingConfig
      buildGradle = buildGradle.replace(
        /release\s*\{[^}]*signingConfig\s+signingConfigs\.debug/,
        (match) => match.replace('signingConfigs.debug',
          'keystorePropertiesFile.exists() ? signingConfigs.release : signingConfigs.debug')
      );
    }

    config.modResults.contents = buildGradle;
    return config;
  });
};
