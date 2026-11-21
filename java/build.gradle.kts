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
    mainClass = "com.leko.forge.java.MainKt"
    applicationName = "forge-javac"
}

tasks.test {
    useJUnitPlatform()
}
