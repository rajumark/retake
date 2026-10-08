# Publishing Retake

Publishing uses the [vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/). One upload to the Maven Central Portal contains every platform: the root `retake` module plus `retake-android`, `retake-jvm`, `retake-iosarm64`, `retake-iossimulatorarm64`, `retake-macosarm64`, `retake-js` and `retake-wasm-js`, each with sources, javadoc, POM and signatures.

Coordinates and POM data are in `gradle.properties` (`GROUP`, `POM_ARTIFACT_ID`, `VERSION_NAME`, `POM_*`).

## Secrets

Put these in `~/.gradle/gradle.properties`, never in the repo:

```properties
mavenCentralUsername=<Central Portal user token username>
mavenCentralPassword=<Central Portal user token password>
signingInMemoryKey=<ASCII-armored GPG private key, newlines replaced by \n>
signingInMemoryKeyPassword=<gpg passphrase>
```

The namespace `io.github.rajumark` must show as *Verified* at https://central.sonatype.com (Namespaces), and the GPG public key must be on keys.openpgp.org or keyserver.ubuntu.com.

## Every release

A Mac is needed, because the Apple targets only build on macOS.

```bash
# 1. bump VERSION_NAME in gradle.properties (and libs.versions.toml in sample/), add a CHANGELOG entry

# 2. library tests on every platform (emulator attached for the device test)
./gradlew :retake:jvmTest :retake:testAndroidHostTest :retake:connectedAndroidDeviceTest \
  :retake:iosSimulatorArm64Test :retake:macosArm64Test \
  :retake:jsNodeTest :retake:jsBrowserTest :retake:wasmJsNodeTest :retake:wasmJsBrowserTest

# 3. dry run: publish to ~/.m2 and run the sample apps against it (see README)
./gradlew :retake:publishToMavenLocal

# 4. upload, then check the deployment at https://central.sonatype.com/publishing and press "Publish"
./gradlew :retake:publishToMavenCentral

# 5. once live (10–30 min), run the sample against Maven Central
cd sample && ./gradlew :androidApp:assembleRelease :desktopApp:run -PretakeRepo=central
```

Tag the release too: `git tag v1.0.0 && git push --tags`.
