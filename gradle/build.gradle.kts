plugins {
    kotlin("jvm") version "2.2.0"
    application
    id("com.gradleup.shadow") version "8.3.7"
}

group = "com.jobbot"
version = "1.1.0"

repositories {
    // LOCAL FIRST: Look in libs directory for your custom TDLib JAR
    flatDir {
        dirs("../libs")
    }
    
    // Fallback to standard repositories
    mavenCentral()
    maven("https://jitpack.io")
}

sourceSets {
    main {
        kotlin {
            // UPDATED: Point to new structure
            setSrcDirs(listOf("../src/main/kotlin"))
        }
        resources {
            setSrcDirs(listOf("../src/resources"))
        }
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    
    // Database
    implementation("org.xerial:sqlite-jdbc:3.50.1.0")
    implementation("com.zaxxer:HikariCP:6.3.0")
    
    // Telegram Bot API v9.x - FIXED: Use correct dependencies
    implementation("org.telegram:telegrambots-longpolling:9.0.0")
    implementation("org.telegram:telegrambots-client:9.0.0")
    
    // Custom TDLib build (local JAR file)
    implementation(files("../libs/tdlib-1.8.0+7973-ssl3.jar"))
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.7")
    
    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.1")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

application {
    // UPDATED: Point to new main class
    mainClass.set("com.jobbot.ApplicationKt")
}

tasks.shadowJar {
    archiveBaseName.set("telegram-job-bot")
    archiveClassifier.set("all")
    mergeServiceFiles()
    
    // Include your custom native library
    from("../libs/natives") {
        include("*.so*")
        into("natives")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}

tasks.test {
    useJUnitPlatform()
}

// Task to verify custom TDLib JAR and native library are available
tasks.register("verifyCustomTdlib") {
    doLast {
        val libsDir = file("../libs")
        val nativesDir = file("../libs/natives")
        
        if (!libsDir.exists()) {
            throw GradleException("libs/ directory not found. Copy your tdlib-1.8.0+7973-ssl3.jar there first.")
        }
        
        val jarFile = file("../libs/tdlib-1.8.0+7973-ssl3.jar")
        if (!jarFile.exists()) {
            throw GradleException("tdlib-1.8.0+7973-ssl3.jar not found in libs/. Copy your custom TDLib JAR there first.")
        }
        
        if (!nativesDir.exists()) {
            throw GradleException("libs/natives/ directory not found. Copy your libtdjni.so there first.")
        }
        
        val soFiles = nativesDir.listFiles { _, name -> name.endsWith(".so") }
        
        if ((soFiles?.size ?: 0) == 0) {
            throw GradleException("No native libraries found in libs/natives/. Copy your libtdjni.so there first.")
        }
        
        println("✅ Custom TDLib verification:")
        println("  📦 ${jarFile.name} (${jarFile.length() / 1024 / 1024}MB)")
        soFiles?.forEach { 
            println("  📦 ${it.name} (${it.length() / 1024 / 1024}MB)")
        }
        
        println("✅ Custom TDLib build will be used instead of Maven dependencies")
    }
}

// Make build depend on custom TDLib verification
tasks.build {
    dependsOn("verifyCustomTdlib")
}
