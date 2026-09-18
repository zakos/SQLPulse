plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Version code from the CI run number, so each artifact can install over the previous one; a local
 * build falls back to 1. VERSION_CODE overrides both. Read from the environment rather than from
 * git, because starting a process at configuration time would break the configuration cache.
 */
val buildNumber = (System.getenv("VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))
    ?.toIntOrNull() ?: 1
val commitSha = System.getenv("GITHUB_SHA")?.take(7)

android {
    namespace = "hu.laurel.sqlpulse"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.laurel.sqlpulse"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNumber
        versionName = listOfNotNull("0.1.$buildNumber", commitSha).joinToString("+")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "hu")
    }

    signingConfigs {
        /**
         * A checked-in debug key, so every build — CI or local — is signed identically and an
         * artifact can replace an installed app. Android refuses an update whose signature differs
         * from the installed one, and a generated debug key differs on every machine and every CI
         * run.
         *
         * It is a debug key with the conventional password and must never sign a release.
         */
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/BC*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.sshj) {
        // Android ships its own (stripped) Bouncy Castle; we pull a full one explicitly.
        exclude(group = "org.bouncycastle")
    }
    // Smaller and more permissively licensed than MySQL Connector/J (§4).
    implementation(libs.mariadb.client) {
        // Pulls in a JDK-only waffle/JNA stack for Windows auth that Android has no use for.
        exclude(group = "com.github.waffle")
        exclude(group = "net.java.dev.jna")
        exclude(group = "org.slf4j")
    }
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.eddsa)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
