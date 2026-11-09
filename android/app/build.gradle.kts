import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun prop(name: String): String = (project.findProperty(name) as String?).orEmpty()
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.ellie.hydration"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ellie.hydration"
        minSdk = 26
        targetSdk = 35
        // CI passes -PellieVersionCode=<run number> so every release can update the installed app.
        versionCode = prop("ellieVersionCode").toIntOrNull() ?: 1
        versionName = "1.0." + (prop("ellieVersionCode").ifBlank { "0" })
        buildConfigField("String", "FIREBASE_APP_ID", quoted(prop("ellie.firebase.appId")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(prop("ellie.firebase.apiKey")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(prop("ellie.firebase.projectId")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(prop("ellie.firebase.senderId")))
        buildConfigField("String", "FROM_NAME", quoted(prop("ellie.fromName")))
        // Only this host may be opened for WHOOP login (prevents a tampered backend
        // from redirecting him to a phishing page).
        buildConfigField("String", "WHOOP_AUTH_HOST", quoted("api.prod.whoop.com"))
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "BACKEND_URL", quoted(prop("ellie.backend.dev")))
            buildConfigField("boolean", "ALLOW_CLEARTEXT_DEV", "true")
        }
        create("qa") {
            dimension = "env"
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            buildConfigField("String", "BACKEND_URL", quoted(prop("ellie.backend.qa")))
            buildConfigField("boolean", "ALLOW_CLEARTEXT_DEV", "false")
        }
        create("uat") {
            dimension = "env"
            applicationIdSuffix = ".uat"
            versionNameSuffix = "-uat"
            buildConfigField("String", "BACKEND_URL", quoted(prop("ellie.backend.uat")))
            buildConfigField("boolean", "ALLOW_CLEARTEXT_DEV", "false")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "BACKEND_URL", quoted(prop("ellie.backend.prod")))
            buildConfigField("boolean", "ALLOW_CLEARTEXT_DEV", "false")
        }
    }

    signingConfigs {
        // Release signing ONLY from environment (CI secret or local shell). Never committed.
        val ks = System.getenv("ELLIE_KEYSTORE_PATH")
        if (!ks.isNullOrBlank()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("ELLIE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ELLIE_KEY_ALIAS")
                keyPassword = System.getenv("ELLIE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.browser:browser:1.8.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
