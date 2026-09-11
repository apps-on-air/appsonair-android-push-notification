# Source notes

The SDK intentionally does not contain:
- Firebase service account credentials
- APNs private keys
- AppsOnAir backend secrets

The host application supplies its Firebase configuration (`google-services.json`).

For a production version, avoid owning the entire FirebaseMessagingService contract if the customer may already have another messaging service. A dispatcher/forwarding integration strategy should be designed and tested before production release.
