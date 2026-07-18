---
trigger: always_on
---

## Gradle JDK Issue

When compiling or running Gradle tasks on the `ApritiSedano` Android project, be aware that using the Antigravity IDE's bundled JRE might cause a build error stating:
`jlink executable ... does not exist`

This is an environment issue and not a Java compilation or syntax error. To prevent or fix this:

1. Ensure `gradle.properties` contains:
   `org.gradle.java.home=C:/Program Files/Android/Android Studio/jre`
2. Ensure `.idea/gradle.xml` specifies the Embedded JDK (jbr-17) using:
   `<option name="gradleJvm" value="jbr-17" />`
3. Remember to advise the user to restart Gradle daemons (`./gradlew --stop`) and run a Gradle Sync from Android Studio if the error ever re-occurs.
