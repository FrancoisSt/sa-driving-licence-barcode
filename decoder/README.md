# decoder: the Kotlin reference

A Kotlin/JVM library, with no dependencies beyond the Kotlin standard library, that follows [../spec](../spec). It
targets Java 17 bytecode, so it runs on any current JVM and on Android (minSdk 26). The [demo](../demo) uses it
as-is.

```kotlin
val result = SaLicenceBarcode.decode(rawBytes)                // throws SaLicenceBarcodeException (e.reason)
when (result) {
    is LicenceBarcode.Card -> {
        val licence = result.licence                           // licenceNumber, validTo, codes, photo, ...
        val findings = LicenceChecks.check(licence)            // spec/checks.md; Finding.blocking
        val portrait = licence.photo?.let(WiPortrait::decode)  // 50,000 bytes, 200 x 250, upright
    }
    is LicenceBarcode.Temporary -> LicenceChecks.check(result.licence)
}
val json = CanonicalJson.of(result)                            // spec/output-format.md
```

| File | What |
|---|---|
| [SaLicenceBarcode.kt](src/main/kotlin/io/github/francoisst/sadl/SaLicenceBarcode.kt) | `identify`, `decrypt`, `parseCard`, `parseTemporaryLicence`, `decode` |
| [Models.kt](src/main/kotlin/io/github/francoisst/sadl/Models.kt) | The result types; their `toString()` holds no personal values |
| [LicenceChecks.kt](src/main/kotlin/io/github/francoisst/sadl/LicenceChecks.kt) | The checks of spec/checks.md |
| [CanonicalJson.kt](src/main/kotlin/io/github/francoisst/sadl/CanonicalJson.kt) | The canonical JSON the test vectors use |
| [WiPortrait.kt](src/main/kotlin/io/github/francoisst/sadl/WiPortrait.kt) | The portrait codec of [../spec/wi-codec.md](../spec/wi-codec.md), pure Kotlin; errors are `WiPortraitException` (`error.code` E1 to E5) |

## Using it in your project

It is not published to a package repository. Copy the five files of
[src/main/kotlin/io/github/francoisst/sadl/](src/main/kotlin/io/github/francoisst/sadl/) into your project (keep the
package name, or change it in all five), or copy the whole `decoder/` directory and add it as a Gradle module. They
need only the Kotlin standard library and Java 17 bytecode; on Android, minSdk 26. Keep the MIT notice.

Everything is thread-safe: the objects hold no mutable state. Exception messages carry no data.

## Tests

```sh
./gradlew :decoder:test                               # synthetic vectors; public vectors when fetched
./gradlew :decoder:test -Psadl.requireVectors=true    # fail instead of skipping without the public vectors
```

They run every file in [../spec/test-vectors](../spec/test-vectors), and, after `scripts/fetch-test-vectors.sh`,
the public vectors: their 24 expected values, the payload, photo and portrait hashes, every WI checkpoint, and the
tamper cases.
