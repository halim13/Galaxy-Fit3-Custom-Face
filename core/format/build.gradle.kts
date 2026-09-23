plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDir("model")
        kotlin.srcDir("parser")
        kotlin.srcDir("validator")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}