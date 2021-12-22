plugins {
    kotlin("plugin.serialization") version "1.6.0"
    kotlin("jvm")
    java
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "sonatype-oss-snapshots"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.6.0")
    implementation("mysql:mysql-connector-java:8.0.25")
    implementation("org.xerial:sqlite-jdbc:3.36.0.2")
    implementation("com.github.jasync-sql:jasync-mysql:2.0.4")
    implementation("com.github.seratch:kotliquery:1.6.0")
    implementation("com.github.HoshiKurama:KyoriComponentDSL:1.1.0")
    implementation("net.kyori:adventure-api:4.9.3")
    implementation("net.kyori:adventure-extra-kotlin:4.9.3")
    implementation("net.kyori:adventure-text-minimessage:4.1.0-SNAPSHOT")
    implementation("org.yaml:snakeyaml:1.29")
    implementation("joda-time:joda-time:2.10.13")
    implementation("dev.kord:kord-core:0.7.4")
    implementation("com.google.code.gson:gson:2.8.9")
}
