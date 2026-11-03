#!/bin/sh
# Simplified launcher — prefer full `gradle wrapper` locally if this fails.
APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Missing gradle-wrapper.jar — run: gradle wrapper --gradle-version 8.7" >&2
  exit 1
fi
exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
