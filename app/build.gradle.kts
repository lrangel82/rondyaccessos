plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

android {
    namespace = "com.larangel.rondyaccesos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.larangel.rondyaccesos"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0 ARGOS"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            // Opcional: Añade estos también de forma preventiva para librerías de Google
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    implementation("io.ktor:ktor-network:2.3.12")
    implementation("io.ktor:ktor-utils:2.3.12")
    implementation("io.ktor:ktor-io:2.3.12")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.3.6")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    //  Esta dependencia específica resuelve el componente PreviewView en el XML
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Opcional: Extensiones para capturas de video o efectos avanzados
    implementation("androidx.camera:camera-extensions:$cameraxVersion")

    implementation("com.google.zxing:core:3.5.3")

    implementation("com.google.api-client:google-api-client:2.4.0")
    implementation("com.google.api-client:google-api-client-android:2.4.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.1")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20260213-2.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

}