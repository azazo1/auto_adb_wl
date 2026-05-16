plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun parseDotEnvLine(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        return null
    }
    val normalized = trimmed.removePrefix("export ").trim()
    val separatorIndex = normalized.indexOf('=')
    if (separatorIndex <= 0) {
        return null
    }
    val key = normalized.substring(0, separatorIndex).trim()
    if (key.isEmpty()) {
        return null
    }
    val value = normalized.substring(separatorIndex + 1)
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
    return key to value
}

val dotEnvValues: Map<String, String> = rootProject.layout.projectDirectory.file(".env").asFile
    .takeIf { it.isFile }
    ?.readLines()
    ?.mapNotNull(::parseDotEnvLine)
    ?.toMap()
    ?: emptyMap()

fun stringBuildConfigField(name: String): String {
    val value = providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: dotEnvValues[name]
        ?: ""
    val escaped = value.trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.azazo1.auto_adb_wl_client"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.azazo1.auto_adb_wl_client"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "AUTO_ADB_WL_LND_BASE_URL",
            stringBuildConfigField("AUTO_ADB_WL_LND_BASE_URL")
        )
        buildConfigField(
            "String",
            "AUTO_ADB_WL_LND_BEARER_TOKEN",
            stringBuildConfigField("AUTO_ADB_WL_LND_BEARER_TOKEN")
        )
        buildConfigField(
            "String",
            "AUTO_ADB_WL_LND_DISCOVERY_DOMAIN",
            stringBuildConfigField("AUTO_ADB_WL_LND_DISCOVERY_DOMAIN")
        )
        buildConfigField(
            "String",
            "AUTO_ADB_WL_LND_SERVICE_NAME",
            stringBuildConfigField("AUTO_ADB_WL_LND_SERVICE_NAME")
        )
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.retrofit2.kotlinx.serialization.converter)
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":lnd-java"))
}
