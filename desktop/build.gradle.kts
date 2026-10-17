// The laptop half of xrnhabit: the same engine (:domain) enforcing the same shared blocks on
// websites (through desktop/extension) and desktop apps (through the KWin script in desktop/kwin).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "xrnhabit-desktop"
    mainClass.set("com.xern.xrnhabit.desktop.MainKt")
    // A small, always-on daemon: keep the heap and the JIT modest.
    applicationDefaultJvmArgs = listOf("-Xmx96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k")
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.unixsocket)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
}
