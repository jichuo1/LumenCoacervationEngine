import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = providers.gradleProperty("lumen.group").orElse("com.lumen.coacervation.engine").get()
version = providers.gradleProperty("lumen.version").get()

android {
    namespace = "com.lumen.coacervation.engine.controls"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()
    buildToolsVersion = providers.gradleProperty("lumen.buildTools").get()
    defaultConfig {
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
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
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":lumen-engine"))
    // 只有这个可选模块依赖 AppCompat / RecyclerView：SwitchCompat 的滑块/轨道 Drawable 接口在框架 Switch 上不同；
    // 拖拽排序基于 ItemTouchHelper。宿主本来就用这两个库时，由这里接到引擎上。
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

// 发布：`publishAllPublicationsToProjectLocalRepository` 输出到根工程 build/repo，宿主把该目录（或私有仓库）加进
// repositories 即可按坐标依赖。坐标与版本规则见 docs/VERSIONING.md。
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "lumen-controls"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("lumen-controls")
                description.set("Optional AppCompat control styling for the Lumen Coacervation Engine.")
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
