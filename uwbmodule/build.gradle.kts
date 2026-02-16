import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

group = "com.dustedrob.uwb"
version = "0.1.0"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "UwbModule"
            isStatic = true
        }
    }
    
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.uwb)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.lifecycle.viewmodel)
            api(libs.moko.permissions)
            api(libs.moko.permissions.compose)
            api(libs.moko.permissions.bluetooth)
            api(libs.moko.permissions.location)
        }
    }
}

android {
    namespace = "com.dustedrob.uwbmodule"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        abortOnError = false
    }
}

publishing {
    publications.withType<MavenPublication> {
        artifactId = if (artifactId == "uwbmodule") "uwb-multiplatform" else artifactId.replace("uwbmodule", "uwb-multiplatform")
        pom {
            name.set("UWB Multiplatform")
            description.set("Kotlin Multiplatform library for Ultra-Wideband (UWB) device discovery and ranging on Android and iOS.")
            url.set("https://github.com/dustedrob/multi-platform-uwb")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("dustedrob")
                    name.set("Roberto Betancourt")
                    email.set("dustedrob@gmail.com")
                }
            }
            scm {
                url.set("https://github.com/dustedrob/multi-platform-uwb")
            }
        }
    }
}