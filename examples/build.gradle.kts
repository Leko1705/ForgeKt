plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":forge"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.leko.tiny.MainKt"
}
