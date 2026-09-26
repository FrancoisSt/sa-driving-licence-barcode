plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    // Java 17 bytecode runs on Android (minSdk 26) and on any current JVM server.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.gson) // reads the JSON vectors in spec/test-vectors/
}

tasks.test {
    systemProperty("sadl.spec", rootProject.file("spec/test-vectors").absolutePath)
    // The public vectors are optional: scripts/fetch-test-vectors.sh puts them here.
    systemProperty("sadl.vectors", rootProject.file("third_party/Reply.Net.SADL").absolutePath)
    systemProperty("sadl.requireVectors", providers.gradleProperty("sadl.requireVectors").getOrElse("false"))
}
