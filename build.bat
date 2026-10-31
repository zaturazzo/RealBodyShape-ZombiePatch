@echo off
rem Build rbs_agent.jar from src\ and pack the release zip. Needs a JDK 11+ (javac); uses JAVA_HOME if set.
setlocal EnableDelayedExpansion
set "HERE=%~dp0"
rem pick the first javac that understands --release 11 (JDK 9+); JDK 8 on PATH is skipped
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" call :try "%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for /f "delims=" %%p in ('where javac 2^>nul') do if not defined JAVAC call :try "%%p"
for %%r in ("%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Eclipse Foundation" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu") do (
    if not defined JAVAC for /d %%d in ("%%~r\jdk*") do if not defined JAVAC if exist "%%d\bin\javac.exe" call :try "%%d\bin\javac.exe"
)
if not defined JAVAC (
    echo javac 11+ not found - install a JDK 11 or newer, or set JAVA_HOME
    exit /b 2
)
goto :build

:try
"%~1" --release 11 -version >nul 2>&1 && set "JAVAC=%~1"
goto :eof

:build
for %%p in ("%JAVAC%") do set "JBIN=%%~dpp"
set "VERSION="
for /f usebackq^ tokens^=2^ delims^=^" %%v in (`findstr /c:"VERSION = " "%HERE%src\rbs\RBSAgent.java"`) do set "VERSION=%%v"
echo javac    : %JAVAC%
echo version  : %VERSION%
if exist "%HERE%build" rmdir /s /q "%HERE%build"
mkdir "%HERE%build"
if exist "%HERE%build\sources.txt" del "%HERE%build\sources.txt"
for %%f in ("%HERE%src\rbs\*.java") do (
    set "SRCF=%%~ff"
    echo "!SRCF:\=/!">> "%HERE%build\sources.txt"
)
"%JAVAC%" -encoding UTF-8 --release 11 -Xlint:-options -d "%HERE%build" "@%HERE%build\sources.txt" || exit /b 1
(echo Manifest-Version: 1.0& echo Premain-Class: rbs.RBSAgent& echo Can-Retransform-Classes: false) > "%HERE%build\MANIFEST.MF"
"%JBIN%jar.exe" cfm "%HERE%dist\rbs_agent.jar" "%HERE%build\MANIFEST.MF" -C "%HERE%build" rbs || exit /b 1
echo built    : %HERE%dist\rbs_agent.jar
set "ZIP=%HERE%RealBodyShapeZombiesPatch-v%VERSION%.zip"
if exist "%ZIP%" del "%ZIP%"
set "LIST="
for %%f in ("%HERE%dist\*") do set LIST=!LIST! "%%~nxf"
"%SystemRoot%\System32\tar.exe" -a -c -f "%ZIP%" -C "%HERE%dist" !LIST! || exit /b 1
echo release  : %ZIP%
