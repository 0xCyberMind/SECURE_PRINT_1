plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.compose")
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.code.gson:gson:2.13.2")
}

val stationAgent = rootProject.file("windows_agent/dist/PrivPrintStationWorker.exe")

tasks.processResources {
  inputs.file(stationAgent)
  from(stationAgent)
}

compose.desktop {
  application {
    mainClass = "com.privprint.windows.MainKt"

    nativeDistributions {
      packageName = "PrivPrint Shop Station"
      packageVersion = "1.0.0"
      description = "PrivPrint secure Windows shop station"
      vendor = "PrivPrint"
      targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
      windows {
        menuGroup = "PrivPrint"
        shortcut = true
        upgradeUuid = "3eb39fc3-c8c0-4c17-8a47-1c685a620c99"
      }
    }
  }
}
