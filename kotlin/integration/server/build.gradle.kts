plugins { kotlin("jvm") version "2.2.20"; application }
repositories { mavenCentral() }
dependencies { implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1") }
kotlin { jvmToolchain(21) }
application { mainClass.set("AllocatorKt") }
