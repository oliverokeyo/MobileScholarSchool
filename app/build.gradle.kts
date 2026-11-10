plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.mobilescholarschool"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mobilescholarschool"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.auth)
    implementation(libs.material)

    // Offline registration: background sync + encrypted storage
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase BoM picks matching versions for Firebase libraries declared WITHOUT a version
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    // Cloud Firestore
    implementation("com.google.firebase:firebase-firestore")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}