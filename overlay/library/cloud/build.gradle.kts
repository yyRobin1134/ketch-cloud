plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinx.serialization)
}

kotlin {
  iosArm64()
  iosSimulatorArm64()
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.serialization.json)
      api(libs.kotlinx.datetime)
      api(libs.ktor.client.core)
      api(libs.okio)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.okhttp)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
  }
}
