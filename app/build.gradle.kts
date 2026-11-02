import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La chiave di firma di rilascio sta FUORI dal progetto; key.properties (ignorato da git) dice dove e con quale password.
// Senza quel file la versione di rilascio non si firma e la build si ferma: è voluto.
val chiave = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.seafilebigupload"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.seafilebigupload"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.5.4"
    }

    signingConfigs {
        create("rilascio") {
            if (chiave.isNotEmpty()) {
                storeFile = file(chiave.getProperty("storeFile"))
                storePassword = chiave.getProperty("storePassword")
                keyAlias = chiave.getProperty("keyAlias")
                keyPassword = chiave.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rilascio")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // i test provano solo logica pura: le classi di Android restituiscono valori vuoti invece di fermare la prova
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
