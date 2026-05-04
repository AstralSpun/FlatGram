import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val keystorePath: String? = localProperties.getProperty("KEYSTORE_PATH")
val apiId: String = localProperties.getProperty("TELEGRAM_API_ID")?: "0"
val apiHash: String = localProperties.getProperty("TELEGRAM_API_HASH")?: "\"Unknown\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.flatgram.messenger"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.flatgram.messenger"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("int", "TELEGRAM_API_ID", apiId)
        buildConfigField("String", "TELEGRAM_API_HASH", apiHash)

        ndk {
            abiFilters.addAll(
                setOf("armeabi-v7a", "arm64-v8a")
            )
        }
        signingConfigs {
            create("release") {
                if (!keystorePath.isNullOrBlank()) {
                    storeFile = file(keystorePath)
                    storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                    keyAlias = localProperties.getProperty("KEY_ALIAS")
                    keyPassword = localProperties.getProperty("KEY_PASSWORD")
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }

            getByName("debug") {
                if (!keystorePath.isNullOrBlank()) {
                    storeFile = file(keystorePath)
                    storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                    keyAlias = localProperties.getProperty("KEY_ALIAS")
                    keyPassword = localProperties.getProperty("KEY_PASSWORD")
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
        }
        externalNativeBuild {
            cmake {
                cppFlags.add("-std=c++17")
                arguments.add("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.td.lib)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}
