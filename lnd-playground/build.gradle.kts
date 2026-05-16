plugins {
    id("application")
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.azazo1.auto_adb_wl_client.lndplayground.Main")
}

dependencies {
    implementation(project(":lnd-java"))
}
