plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
}

allprojects {
    group = "com.leko"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
