# Phone Doctor — By PaliaAPK HUB

**Developer By shanpalia**

Native Android diagnostics built with Kotlin and Jetpack Compose, in a white + mint-green theme.

## Current implementation
- Reads actual device manufacturer/model and Android version from Android APIs.
- Reads system memory availability, data-partition storage, battery percentage when available, and current network transport.
- Flags threshold-based storage/RAM/battery notices only when measured values support them.
- Labels permissions and system-update checks as manual guidance. This starter is not an antivirus and does not invent installed-app/permission results.
- Does not silently delete files or change system settings.

## Build
Open in Android Studio or use the GitHub Actions workflow. JDK 17 and Android Gradle Plugin 8.7.3 are used. GitHub Actions builds a debug APK and uploads it as a workflow artifact.

Package: com.paliaapk.phonedoctor

## Limitations
Android limits what regular apps can inspect and repair. Some details are hidden by OS version/manufacturer; this app gives Settings-based instructions rather than claiming unrestricted auto-fix.
