import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = providers.gradleProperty("lumen.group").orElse("com.lumen.coacervation.engine").get()
version = providers.gradleProperty("lumen.version").get()

android {
    namespace = "com.lumen.coacervation.engine.motion"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()
    defaultConfig {
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // 契约测试按相对模块目录的路径读取生产源码（见 SourceContract）。
        unitTests.all { it.workingDir = projectDir }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":lumen-engine"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

// 发布：`publishAllPublicationsToProjectLocalRepository` 输出到根工程 build/repo，见 docs/VERSIONING.md。
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "lumen-motion"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("lumen-motion")
                description.set("Interaction and motion layer of the Lumen Coacervation Engine: long-press elastic deformation with touch highlight, interruptible motion, open/close morphs.")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "projectLocal"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
