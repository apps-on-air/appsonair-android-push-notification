# Bundled AndroidSVG 1.4

A copy of [AndroidSVG](https://github.com/BigBadaboom/androidsvg) 1.4 by Paul LeBeau,
Cave Rock Software Ltd., licensed under the Apache License 2.0 (see each file's header).

It is bundled instead of declared as a dependency because the library is published under
two names, `com.caverock:androidsvg` and `com.caverock:androidsvg-aar`, with the same classes.
Host apps already pull in one or the other (react-native-fast-image, expo-image, coil-svg),
so depending on either name fails some apps with "Duplicate class com.caverock.androidsvg…".
Under its own package the copy can never clash.

Changes from upstream: the package was renamed from `com.caverock.androidsvg`, and
`SVGImageView` was left out (it needs layout resources and the SDK doesn't use it).

To update, take the `-sources.jar` of the new version from Maven Central and repeat both changes.
