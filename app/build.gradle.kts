plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Imzo kaliti faqat CI'da (GitHub sirlaridan) beriladi; lokal yoki test uchun ixtiyoriy.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val hasKeystore = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

android {
    namespace = "uz.alifbo.klaviatura"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.alifbo.klaviatura"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasKeystore) {
            create("alifbo") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "alifbo"
                keyPassword = keystorePassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("alifbo")
        }
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("alifbo")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.emoji2:emoji2-emojipicker:1.4.0")

    testImplementation("junit:junit:4.13.2")
}
