plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.main.get().kotlin.srcDir("${rootDir}/bdk-src/main/kotlin")
}

dependencies {
    implementation(project(":logic"))
    implementation("org.bitcoindevkit:bdk-jvm:3.1.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}
