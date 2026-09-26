plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.francoisst.sadl.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.francoisst.sadl.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so that `assembleRelease` gives an installable demo. Use your own key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        // The public vectors (git-ignored, from scripts/fetch-test-vectors.sh) and the spec's hashes, for the device
        // test only: they are never packaged into the app.
        getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/testVectors"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":decoder"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.cpp)
    // Optional second engine: Google ML Kit with its bundled model (offline, no Play services). It reads worn cards
    // that zxing-cpp finds but cannot correct. Its terms: https://developers.google.com/ml-kit/terms
    implementation(libs.mlkit.barcode)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.gson)
}

val copyTestVectors by tasks.registering(Copy::class) {
    from(rootProject.file("third_party/Reply.Net.SADL/Reply.Net.SADL/Reply.Net.SADL.Tests")) { include("UnitTest1.cs") }
    from(rootProject.file("spec/test-vectors")) { include("public-vectors.json", "wi-synthetic.json") }
    into(layout.buildDirectory.dir("generated/testVectors"))
}
tasks.matching { it.name.startsWith("merge") && it.name.contains("AndroidTest") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyTestVectors) }
