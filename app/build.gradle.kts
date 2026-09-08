plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val backupServerUrl = providers.gradleProperty("NODEMAP_BACKUP_SERVER_URL")
    .orElse(providers.environmentVariable("NODEMAP_BACKUP_SERVER_URL"))
    .orElse("")
val googleServerClientId = providers.gradleProperty("NODEMAP_GOOGLE_SERVER_CLIENT_ID")
    .orElse(providers.environmentVariable("NODEMAP_GOOGLE_SERVER_CLIENT_ID"))
    .orElse("")

android {
    namespace = "kr.mooner510"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.mooner510"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        buildConfigField("String", "BACKUP_SERVER_URL", backupServerUrl.get().asBuildConfigString())
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleServerClientId.get().asBuildConfigString())
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.maplibre.gl:android-sdk-opengl:13.4.1")
}
