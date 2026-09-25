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
        versionCode = 42
        versionName = "0.6.36"
    }
    signingConfigs {
        if (hasPrivateSigning) create("lapibreizh") {
            storeFile=file(privateKeyFile!!);storePassword=privateKeyPassword
            keyAlias="lapibreizh";keyPassword=privateKeyPassword
        }
    }
    buildTypes { getByName("release") { isMinifyEnabled=false;if(hasPrivateSigning)signingConfig=signingConfigs.getByName("lapibreizh") } }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

/* V0.6.36 : applique d'abord V0.6.35, puis le correctif ciblé V0.6.36. */
tasks.named("preBuild").configure {
    doFirst {
        exec {
            workingDir(rootProject.projectDir)
            commandLine("python3", "app/apply_v0635.py")
        }
        exec {
            workingDir(rootProject.projectDir)
            commandLine("python3", "app/apply_v0636.py")
        }
    }
}
