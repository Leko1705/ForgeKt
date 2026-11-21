plugins {
    kotlin("multiplatform")
    `maven-publish`
}

kotlin {
    explicitApi()

    jvmToolchain(21)

    jvm()
    js {
        nodejs()
    }
    // Further targets (native, wasm) only need the small platform layer, see README.md.

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(kotlin("reflect"))
            implementation("org.ow2.asm:asm:9.10.1")       // MSI → JVM bytecode backend
            implementation("org.ow2.asm:asm-tree:9.10.1")
        }
    }
}
