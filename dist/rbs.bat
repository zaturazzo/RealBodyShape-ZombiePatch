@echo off
rem Real Body Shape - Zombie bodies patch (Project Zomboid Build 42, Windows)
rem usage: rbs.bat install|uninstall|status ["<game folder>"]
rem Uses the game's own jre64 - no Java installation needed. Builds rbs_patch.jar from YOUR projectzomboid.jar
rem and lists it first in ProjectZomboid64.json "classpath" (backup: ProjectZomboid64.json.rbs_backup).
setlocal EnableDelayedExpansion
set "HERE=%~dp0"
set "MODE=%~1"
if "%MODE%"=="" set "MODE=status"
set "PZ=%~2"
if not defined PZ call :findgame
if not defined PZ (
    echo Project Zomboid folder not found. Run again with the folder, e.g.
    echo    %~nx0 %MODE% "D:\SteamLibrary\steamapps\common\ProjectZomboid"
    exit /b 2
)
if not exist "%PZ%\projectzomboid.jar" (
    echo projectzomboid.jar not found in "%PZ%"
    exit /b 2
)
set "JAVA=%PZ%\jre64\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
"%JAVA%" -cp "%HERE%rbs_agent.jar;%PZ%\projectzomboid.jar" rbs.MakePatch %MODE% "%PZ%"
exit /b %errorlevel%

:findgame
if exist "%HERE%projectzomboid.jar" (
    set "PZ=%HERE:~0,-1%"
    goto :eof
)
if exist "%HERE%..\projectzomboid.jar" (
    set "PZ=%HERE%.."
    goto :eof
)
set "STEAM="
for /f "tokens=2*" %%a in ('reg query "HKCU\Software\Valve\Steam" /v SteamPath 2^>nul') do set "STEAM=%%b"
if defined STEAM set "STEAM=!STEAM:/=\!"
if defined STEAM (
    if exist "!STEAM!\steamapps\common\ProjectZomboid\projectzomboid.jar" (
        set "PZ=!STEAM!\steamapps\common\ProjectZomboid"
        goto :eof
    )
    if exist "!STEAM!\steamapps\libraryfolders.vdf" call :scanvdf "!STEAM!\steamapps\libraryfolders.vdf"
)
if not defined PZ if exist "%ProgramFiles(x86)%\Steam\steamapps\common\ProjectZomboid\projectzomboid.jar" set "PZ=%ProgramFiles(x86)%\Steam\steamapps\common\ProjectZomboid"
goto :eof

:scanvdf
rem lines look like:   "path"   "D:\\SteamLibrary"   -> split on quotes, the value follows the "path" key
for /f usebackq^ tokens^=1-4^ delims^=^" %%a in (`findstr /i path "%~1"`) do (
    set "LIB="
    if "%%a"=="path" set "LIB=%%c"
    if "%%b"=="path" set "LIB=%%d"
    if defined LIB (
        set "LIB=!LIB:\\=\!"
        if not defined PZ if exist "!LIB!\steamapps\common\ProjectZomboid\projectzomboid.jar" set "PZ=!LIB!\steamapps\common\ProjectZomboid"
    )
)
goto :eof
