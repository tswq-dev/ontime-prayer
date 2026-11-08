// Library build for JitPack (see jitpack.yml). The port itself is the single file UmmAlQura.kt.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "com.github.tswq-dev"
version = System.getenv("VERSION") ?: "1.0.0"

repositories { mavenCentral() }

sourceSets { main { kotlin { setSrcDirs(listOf(".")); include("UmmAlQura.kt") } } }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

// Readable by apps on Kotlin 1.9 and later.
kotlin {
    coreLibrariesVersion = "1.9.24"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "ontime-prayer"
            from(components["java"])
        }
    }
}
