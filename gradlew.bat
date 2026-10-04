@rem ============================================================
@rem  PrivPrint — Gradle wrapper entry point (Windows)
@rem
@rem  The repo ships only the Gradle wrapper *distribution*
@rem  (unpacked under ~/.gradle/wrapper/dists/gradle-9.3.1-bin/...).
@rem  This batch file drives that pre-unpacked distribution so
@rem  the documented "./gradlew" command works on Windows without
@rem  the wrapper jar being committed.
@rem ============================================================
@echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
pushd "%DIRNAME%"
set DIRNAME=%CD%
popd

set GRADLE_HOME=%DIRNAME%\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo [gradlew] Gradle distribution not found at %GRADLE_HOME%
  echo [gradlew] Expected layout: .gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle.bat
  echo [gradlew] Run "gradle wrapper" from a machine with Gradle installed, or extract the distribution.
  exit /b 1
)

set GRADLE_OPTS=-Xmx4g -Dfile.encoding=UTF-8
set JAVA_HOME=C:\Program Files\Java\jdk-17.0.5

"%GRADLE_HOME%\bin\gradle.bat" %* --no-daemon
if errorlevel 1 exit /b %ERRORLEVEL%

endlocal
