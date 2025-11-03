const { withAndroidManifest } = require('@expo/config-plugins');

/**
 * Expo config plugin to add DownloadService and DownloadActionReceiver to AndroidManifest.xml
 *
 * This ensures that when running `npx expo prebuild`, the service and receiver are properly
 * declared in the generated AndroidManifest.xml.
 */
const withDownloadService = (config) => {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;
    const application = androidManifest.manifest.application[0];

    // Add DownloadService
    const serviceExists = application.service?.some(
      (service) => service.$['android:name'] === 'expo.modules.rustbridge.DownloadService'
    );

    if (!serviceExists) {
      if (!application.service) {
        application.service = [];
      }

      application.service.push({
        $: {
          'android:name': 'expo.modules.rustbridge.DownloadService',
          'android:exported': 'false',
          'android:foregroundServiceType': 'dataSync',
        },
      });
    }

    // Add DownloadActionReceiver
    const receiverExists = application.receiver?.some(
      (receiver) => receiver.$['android:name'] === 'expo.modules.rustbridge.DownloadActionReceiver'
    );

    if (!receiverExists) {
      if (!application.receiver) {
        application.receiver = [];
      }

      application.receiver.push({
        $: {
          'android:name': 'expo.modules.rustbridge.DownloadActionReceiver',
          'android:exported': 'false',
        },
        'intent-filter': [
          {
            action: [
              { $: { 'android:name': 'expo.modules.rustbridge.ACTION_PAUSE' } },
              { $: { 'android:name': 'expo.modules.rustbridge.ACTION_RESUME' } },
              { $: { 'android:name': 'expo.modules.rustbridge.ACTION_CANCEL' } },
            ],
          },
        ],
      });
    }

    return config;
  });
};

module.exports = withDownloadService;
