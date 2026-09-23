plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.galaxyfit3.core.delivery"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:format"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Samsung Accessory SDK JARs — proprietary, fetched from mirror (see libs/README.md).
    implementation(files(rootProject.layout.projectDirectory.dir("libs").file("accessory-v2.6.4.jar").asFile))
    implementation(files(rootProject.layout.projectDirectory.dir("libs").file("sdk-v1.0.0.jar").asFile))

    testImplementation(libs.junit)
}