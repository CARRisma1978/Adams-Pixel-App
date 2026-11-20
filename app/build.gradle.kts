import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.adamcarr.pixelapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adamcarr.pixelapp"
        minSdk = 31
        // Deliberately old. Android only lets apps toggle Bluetooth (blocked from 33)
        // and Wi-Fi (blocked from 29) if they target an older version.
        targetSdk = 28
        versionCode = 5
        versionName = "1.4"
        // The Pixel 9 Pro is 64-bit ARM only; leave out native code for other chips.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing details live in keystore.properties at the project root, which is
    // not committed. Without it, builds are signed with the standard debug key instead.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val hasReleaseKey = keystoreProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
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
            // Metadata only needed by Kotlin reflection, debuggers and licence viewers.
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/versions/**",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.txt",
                "DebugProbesKt.bin",
            )
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.github.mwiede:jsch:0.2.26")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
