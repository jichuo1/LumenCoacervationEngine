import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lumen.coacervation.sample"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()

    defaultConfig {
        applicationId = "com.lumen.coacervation.sample"
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("lumen.targetSdk").get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("lumen.version").get()
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
    implementation(project(":lumen-engine"))
    implementation(project(":lumen-controls"))
    implementation(project(":lumen-motion"))
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
