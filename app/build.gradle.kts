plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 34
    namespace = "com.example.ld1"
    defaultConfig {
        applicationId = "com.example.ld1"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildFeatures { viewBinding = true }   // <-- must be here
    }
    buildFeatures { viewBinding = true }  // <- required for ActivityMainBinding
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0") // for CoordinatorLayout
}
