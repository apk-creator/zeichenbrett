plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.zeichenbrett"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.zeichenbrett"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("zb") {
            storeFile = file("zeichenbrett.jks")
            storePassword = "zeichenbrett"
            keyAlias = "zb"
            keyPassword = "zeichenbrett"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("zb")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zb")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
