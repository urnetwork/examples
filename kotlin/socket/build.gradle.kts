plugins {
    kotlin("jvm") version "2.2.20"
    application
}
repositories { mavenLocal(); mavenCentral() }
dependencies {
    implementation("io.ur:urnetwork-sdk:" + (findProperty("sdkVersion") ?: "0.0.1-dev.0"))
    implementation("io.ktor:ktor-client-okhttp:3.3.0")
    implementation("io.ktor:ktor-client-core:3.3.0")
}
kotlin { jvmToolchain(21) }
sourceSets.main { java.srcDir("../integration/client") }
application { mainClass.set("MainKt") }
