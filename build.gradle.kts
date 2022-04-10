import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
   kotlin("jvm") version "1.6.20"
   java
}

repositories {
   mavenCentral()
}

dependencies {
   implementation("org.jetbrains.kotlin:kotlin-stdlib:1.6.20")
}

subprojects {
   group = "com.github.hoshikurama"
   version = "8.0.3"

   tasks.withType<KotlinCompile> {
      kotlinOptions.jvmTarget = "17"
   }

   tasks.withType<KotlinCompile>().configureEach {
      kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
   }
}