// The Kotlin provider example. `gradle installDist` builds
// build/install/urnetwork-provider/bin/urnetwork-provider (and .bat on
// Windows); `gradle build` also runs the credential-free self-test.
plugins {
    kotlin("jvm") version "2.2.20"
    application
}
repositories { mavenLocal(); mavenCentral() }
dependencies {
    // the version of a local SDK build; select a release with -PsdkVersion
    implementation("io.ur:urnetwork-sdk:" + (findProperty("sdkVersion") ?: "0.0.1-dev.0"))
    // JSON elements only; no serialization compiler plugin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("MainKt") }
val selfTest = tasks.register<JavaExec>("selfTest") {
    description = "Runs the credential-free provider self-test."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
    args("--self-test")
}
tasks.check { dependsOn(selfTest) }
