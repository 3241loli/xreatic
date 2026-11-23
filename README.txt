LabHop - Fabric 1.21.11 (client only)

Build:
  1. Install JDK 21.
  2. Easiest: generate a Fabric template for 1.21.11 at https://fabricmc.net/develop/template
     (Mojang mappings), then replace its src/ folder, build.gradle and gradle.properties with these.
     Or run "gradle wrapper" here if you have Gradle installed.
  3. ./gradlew build   ->   build/libs/labhop-1.0.0.jar
  4. Put the jar plus Fabric API into your mods folder.

Use:  /lab on a server = hop into a private flat creative world, /lab again = back.
