plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val privateKeyFile = System.getenv("LAPIBREIZH_KEYSTORE_PATH")
val privateKeyPassword = System.getenv("LAPIBREIZH_KEYSTORE_PASSWORD")
val hasPrivateSigning = !privateKeyFile.isNullOrBlank() && !privateKeyPassword.isNullOrBlank()
android {
    namespace = "fr.leslapibreizh.mediatheque"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.leslapibreizh.mediatheque"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.6.2"
    }
    signingConfigs {
        if (hasPrivateSigning) {
            create("lapibreizh") {
                storeFile = file(privateKeyFile!!)
                storePassword = privateKeyPassword
                keyAlias = "lapibreizh"
                keyPassword = privateKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasPrivateSigning) signingConfig = signingConfigs.getByName("lapibreizh")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
