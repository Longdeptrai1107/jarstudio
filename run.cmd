@echo off
setlocal
set "JARSTUDIO_DIR=%~dp0"
if exist "%JARSTUDIO_DIR%dist\windows\JarStudio\JarStudio.exe" (
  start "" "%JARSTUDIO_DIR%dist\windows\JarStudio\JarStudio.exe" %*
  exit /b 0
)
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" (
  start "" "%JAVA_HOME%\bin\javaw.exe" --add-modules=jdk.jdi,jdk.compiler -Xmx2g -jar "%JARSTUDIO_DIR%dist\JarStudio.jar" %*
  exit /b 0
)
for /d %%J in ("%LOCALAPPDATA%\Programs\RecafRuntime\jdk-*") do (
  if exist "%%~J\bin\javaw.exe" (
    start "" "%%~J\bin\javaw.exe" --add-modules=jdk.jdi,jdk.compiler -Xmx2g -jar "%JARSTUDIO_DIR%dist\JarStudio.jar" %*
    exit /b 0
  )
)
java --add-modules=jdk.jdi,jdk.compiler -Xmx2g -jar "%JARSTUDIO_DIR%dist\JarStudio.jar" %*
if errorlevel 1 pause
