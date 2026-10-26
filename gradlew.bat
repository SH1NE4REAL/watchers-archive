@rem Project-local launcher; uses pinned local Gradle if bootstrap.py has run.
@echo off
setlocal
set "APP_HOME=%~dp0"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%APP_HOME%.tools\gradle-home"
if exist "%APP_HOME%.tools\gradle-8.9\bin\gradle.bat" (
  call "%APP_HOME%.tools\gradle-8.9\bin\gradle.bat" %*
  exit /b %ERRORLEVEL%
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
