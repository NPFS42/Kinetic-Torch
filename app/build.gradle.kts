plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.npfs.stars.kinetictorch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.npfs.stars.kinetictorch"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.0"
        buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "false")
    }

    // Use the existing shop upload/release key without committing any signing material.
    val keyPath = System.getenv("KINETIC_KEYSTORE_PATH")
    if (!keyPath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keyPath)
                storePassword = requireNotNull(System.getenv("KINETIC_KEYSTORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("KINETIC_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("KINETIC_KEY_PASSWORD"))
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "true")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug-logging"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("diagnostic") {
            initWith(getByName("release"))
            isDebuggable = false
            versionNameSuffix = "-diagnostic"
            buildConfigField("boolean", "DIAGNOSTICS_ENABLED", "true")
            matchingFallbacks += listOf("release")
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

