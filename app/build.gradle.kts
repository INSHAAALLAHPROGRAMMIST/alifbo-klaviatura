plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Imzo kaliti faqat CI'da (GitHub sirlaridan) beriladi; lokal yoki test uchun ixtiyoriy.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val hasKeystore = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

// Standart Google Tarjima kaliti ham faqat CI'da (GitHub sirlaridan) qotiriladi; manbada yo'q.
// Foydalanuvchi sozlamadan o'zining kalitini kiritsa, shu standart kalit e'tiborga olinmaydi.
val defaultGoogleKey: String = (System.getenv("GOOGLE_TRANSLATE_KEY") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "uz.alifbo.klaviatura"
    compileSdk = 35

    defaultConfig {
        // "Söz+" (toliq branch): asosiy "Söz" bilan yonma-yon o'rnatiladi
        applicationId = "uz.alifbo.klaviatura.toliq"
        minSdk = 26
        targetSdk = 34
        // CI'dagi har yig'ish raqami: yangi versiya eskisining ustiga o'rnatilishi uchun o'sib boradi
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "0.4.$run"
        buildConfigField("String", "DEFAULT_GOOGLE_KEY", "\"$defaultGoogleKey\"")
    }

    buildFeatures {
        buildConfig = true
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.emoji2:emoji2-emojipicker:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
