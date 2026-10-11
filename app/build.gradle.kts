plugins {
    id("com.android.application")
}

android {
    namespace = "com.ispina.mldcheck"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.ispina.mldcheck"
        minSdk = 23
        targetSdk = 35
        versionCode = 20000
        versionName = "2.0.0-preview"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            applicationIdSuffix = ".direct"
        }
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
