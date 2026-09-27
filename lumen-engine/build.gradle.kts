import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

val lumenVersion = providers.gradleProperty("lumen.version").get()
group = "com.lumen.coacervation.engine"
version = lumenVersion

android {
    namespace = "com.lumen.coacervation.engine"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "LUMEN_VERSION", "\"$lumenVersion\"")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

// 发布：`publishAllPublicationsToProjectLocalRepository` 输出到根工程 build/repo，宿主把该目录（或私有仓库）加进
// repositories 即可按坐标依赖。坐标与版本规则见 docs/VERSIONING.md。
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "lumen-engine"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("lumen-engine")
                description.set("Lumen Coacervation Engine: portable glass material and visual effects engine for Android View-based apps.")
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
