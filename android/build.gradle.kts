import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.google.services)     apply false
}

// Shared secret sent to the alarm server as X-API-Key. Read only from
// android/local.properties (gitignored) so it never reaches git or CI artifacts:
//   alarm.apiKey=<same value as ALARM_API_KEY on the server>
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
extra["alarmApiKey"] = localProperties.getProperty("alarm.apiKey", "").trim()
