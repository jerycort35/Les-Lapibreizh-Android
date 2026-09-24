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
        versionCode = 36
        versionName = "0.6.30"
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
val applyV0629 by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "app/apply_v0629.py")
}
val applyV0630 by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "app/apply_v0630.py")
}
applyV0630.configure { dependsOn(applyV0629) }
tasks.named("preBuild").configure { dependsOn(applyV0630) }
