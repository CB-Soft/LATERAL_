plugins {
    alias(libs.plugins.android.application)
}

val vitureSdkEnabled = providers.gradleProperty("lateral.vitureSdk")
    .orElse("true")
    .map(String::toBoolean)
    .get()
val vitureVariantRequested = gradle.startParameter.taskNames.any {
    it.contains("viture", ignoreCase = true)
}
if (vitureVariantRequested && !vitureSdkEnabled) {
    throw GradleException("The VITURE variant requires -Plateral.vitureSdk=true and the local VITURE SDK.")
}

android {
    namespace = "com.lateral"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    flavorDimensions += "edition"

    productFlavors {
        create("stable") {
            dimension = "edition"
            applicationId = "com.lateral"
            manifestPlaceholders["lateralPrivilegedAuthority"] = "com.lateral.privileged"
            manifestPlaceholders["lateralPairingAction"] = "com.lateral.privileged.SUBMIT_PAIRING_CODE"
            manifestPlaceholders["lateralBeastTaskAffinity"] = "com.lateral.beast"
            manifestPlaceholders["lateralLauncherIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["lateralLauncherRoundIcon"] = "@mipmap/ic_launcher_round"
        }
        create("dev") {
            dimension = "edition"
            applicationId = "com.lateral.dev"
            manifestPlaceholders["lateralPrivilegedAuthority"] = "com.lateral.dev.privileged"
            manifestPlaceholders["lateralPairingAction"] = "com.lateral.dev.privileged.SUBMIT_PAIRING_CODE"
            manifestPlaceholders["lateralBeastTaskAffinity"] = "com.lateral.dev.beast"
            manifestPlaceholders["lateralLauncherIcon"] = "@mipmap/ic_launcher_dev"
            manifestPlaceholders["lateralLauncherRoundIcon"] = "@mipmap/ic_launcher_dev_round"
        }
        create("viture") {
            dimension = "edition"
            // The V variant replaces the public stable APK on-device while
            // advertising its optional, SDK-backed feature set in the launcher.
            applicationId = "com.lateral"
            manifestPlaceholders["lateralPrivilegedAuthority"] = "com.lateral.privileged"
            manifestPlaceholders["lateralPairingAction"] = "com.lateral.privileged.SUBMIT_PAIRING_CODE"
            manifestPlaceholders["lateralBeastTaskAffinity"] = "com.lateral.beast"
            manifestPlaceholders["lateralLauncherIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["lateralLauncherRoundIcon"] = "@mipmap/ic_launcher_round"
        }
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.2.3"
        buildConfigField("boolean", "VITURE_SDK_ENABLED", vitureSdkEnabled.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DLATERAL_ENABLE_VITURE=$vitureSdkEnabled"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    if (vitureSdkEnabled) {
        sourceSets {
            getByName("main").jniLibs.srcDirs("../../uxspace/Android/glasses/src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("com.lateral.thirdparty:florisboard-embedded:0.5.2-lateral.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.sun.security.android)
    testImplementation("junit:junit:4.13.2")
}
