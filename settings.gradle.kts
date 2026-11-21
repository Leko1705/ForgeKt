plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ForgeKt"

include(":forge")      // the library
include(":examples")   // Tiny, a small language built with the library
include(":java")       // a Java compiler built with the library
include(":python")     // a Python compiler (to JavaScript) built with the library
