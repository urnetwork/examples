// The Kotlin embed backend tool. `gradle installDist` builds
// build/install/urnetwork-embed-server/bin/urnetwork-embed-server (and .bat on
// Windows); `gradle build` also runs the credential-free self-test.
plugins { kotlin("jvm") version "2.2.20"; application }
repositories { mavenCentral() }
dependencies { implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1") }
kotlin { jvmToolchain(21) }
application { mainClass.set("EmbedServerKt") }
val selfTest = tasks.register<JavaExec>("selfTest") {
    description = "Runs the backend tool's credential-free self-test."
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("EmbedServerKt")
    args("--self-test")
}
tasks.check { dependsOn(selfTest) }
