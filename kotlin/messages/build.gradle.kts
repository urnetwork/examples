plugins { kotlin("jvm") version "2.2.20"; application }
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("io.ur:urnetwork-sdk:" + (findProperty("sdkVersion") ?: "0.0.1-dev.0")) }
kotlin { jvmToolchain(21) }
sourceSets.main {
    java.srcDirs("../integration/client", "../../java/messages")
    java.include("UrSession.java", "UrMessages.java", "MessageCodec.java")
}
application { mainClass.set("MainKt") }
