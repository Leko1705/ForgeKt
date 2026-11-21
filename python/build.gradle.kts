plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":forge"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.leko.forge.python.MainKt"
    applicationName = "forge-python"
}

tasks.test {
    useJUnitPlatform()
}
