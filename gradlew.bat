@echo off
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
  echo Missing gradle-wrapper.jar — run: gradle wrapper --gradle-version 8.7
  exit /b 1
)
java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
