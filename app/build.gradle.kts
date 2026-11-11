import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

val requiredSigningProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasValidReleaseSigning = keystorePropertiesFile.exists() &&
    requiredSigningProps.all { !keystoreProperties.getProperty(it).isNullOrBlank() } &&
    file(keystoreProperties.getProperty("storeFile")).exists()

android {
    namespace = "com.medusko.keyboardy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medusko.keyboardy"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.1-RC2-Beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("Boolean", "SHOW_ADS", "true")
        buildConfigField("Boolean", "ENABLE_NSFW", "true")
    }

    signingConfigs {
        create("release") {
            if (hasValidReleaseSigning) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasValidReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

gradle.taskGraph.whenReady {
    val releaseBuildTasks = setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease",
        "packageReleaseBundle",
        "signReleaseBundle",
        "validateSigningRelease"
    )
    val runningReleasePackaging = allTasks.any { task ->
        task.project == project && task.name in releaseBuildTasks
    }
    if (runningReleasePackaging && !hasValidReleaseSigning) {
        throw GradleException(
            "Release signing is not configured: missing keystore.properties, required signing properties, " +
                "or keystore file. Refusing to fall back to debug signing."
        )
    }
}
