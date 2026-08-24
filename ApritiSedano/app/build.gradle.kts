plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "it.geniola.apritisedano"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "it.geniola.apritisedano"
        minSdk = 35
        targetSdk = 36
        versionCode = 4
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("androidx.car.app:app:1.4.0")
    implementation(libs.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
}

tasks.register("deployForAndroidAuto") {
    // Assicura che l'app venga compilata prima dell'esecuzione
    dependsOn("assembleDebug")
    group = "Android Auto"
    description = "Compila, installa simulando il Play Store e riavvia Android Auto"

    // 1. FASE DI CONFIGURAZIONE:
    // Usiamo le nuove API "androidComponents" per ottenere il riferimento (Provider) ad ADB
    val androidComponents = project.extensions.getByType(com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java)
    val adbProvider = androidComponents.sdkComponents.adb

    // Otteniamo il riferimento (Provider) alla cartella di build
    val buildDirProvider = layout.buildDirectory

    doLast {
        // 2. FASE DI ESECUZIONE:
        // Chiamiamo ".get()" sui Provider. Questo è il metodo corretto per il Configuration Cache.
        val adbPath = adbProvider.get().asFile.absolutePath
        val apkPath = "${buildDirProvider.get().asFile.absolutePath}/outputs/apk/debug/app-debug.apk"

        println("--> 1/2: Installando l'APK mascherato da Play Store...")
        val installProcess = ProcessBuilder(adbPath, "install", "-t", "-i", "com.android.vending", "-r", apkPath)
            .inheritIO()
            .start()
        
        if (installProcess.waitFor() != 0) {
            throw GradleException("Errore durante l'installazione dell'APK. Assicurati che lo smartphone sia collegato e che il debug USB sia attivo.")
        }

        println("--> 2/2: Forzando l'arresto di Android Auto per aggiornare il launcher...")
        val forceStopProcess = ProcessBuilder(adbPath, "shell", "am", "force-stop", "com.google.android.projection.gearhead")
            .inheritIO()
            .start()
            
        if (forceStopProcess.waitFor() != 0) {
            throw GradleException("Errore durante l'arresto forzato di Android Auto.")
        }

        println("--> Deploy completato! L'app è pronta per essere provata in auto.")
    }
}