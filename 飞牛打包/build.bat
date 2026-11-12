@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

echo.
echo ==========================================
echo   fnOS FPK Packer (Native / JRE)
echo ==========================================
echo.

REM fpk 使用 -Pfnos 产出的 Linux-only jar（不含 Windows FFmpeg native）
REM 默认 ..\target\invr.jar 仍是双平台开发包，本地调试能力不变
if not exist "..\target\invr-fnos.jar" (
    echo [ERROR] ..\target\invr-fnos.jar not found
    echo Please run: mvn clean package -Pfnos
    pause
    exit /b 1
)
powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $z = [IO.Compression.ZipFile]::OpenRead((Resolve-Path '..\target\invr-fnos.jar').Path); $bad = @($z.Entries | Where-Object { $_.FullName -match 'windows-x86_64' }).Count; $z.Dispose(); if ($bad -gt 0) { exit 1 }"
if errorlevel 1 (
    echo [ERROR] fnos jar still contains Windows FFmpeg native, rebuild with mvn -Pfnos
    pause
    exit /b 1
)
echo [OK] invr-fnos.jar found (Linux-only)

if not exist "input\jre\bin\java" (
    echo [ERROR] input\jre\bin\java not found
    echo Please extract Linux x64 JRE to input\jre\
    echo NOTE: must be Linux version, not Windows!
    pause
    exit /b 1
)
echo [OK] JRE found

if exist "build" rmdir /s /q "build"
mkdir "build"

echo [..] Copying template...
xcopy "template\*" "build\" /E /I /Y /Q >nul

echo [..] Copying JRE and jar...
xcopy "input\jre\*" "build\app\jre\" /E /I /Y /Q >nul
copy "..\target\invr-fnos.jar" "build\app\app.jar" >nul
copy "template\manifest" "build\app\manifest" >nul

REM 只删 fnOS 永不命中的 CDS 变体（堆 > 32GB 才用）；classes.jsa / classes_coh.jsa 保留以加速启动
echo [..] Trimming unused CDS archives...
if exist "build\app\jre\lib\server\classes_nocoops.jsa" del /q "build\app\jre\lib\server\classes_nocoops.jsa"
if exist "build\app\jre\lib\server\classes_nocoops_coh.jsa" del /q "build\app\jre\lib\server\classes_nocoops_coh.jsa"

echo [..] Syncing ui/config from manifest...
powershell -NoProfile -Command "$m = Get-Content 'template\manifest'; $appName = ($m | Select-String '^appname=(.+)').Matches.Groups[1].Value.Trim(); $port = ($m | Select-String '^service_port=(.+)').Matches.Groups[1].Value.Trim(); $title = ($m | Select-String '^display_name=(.+)').Matches.Groups[1].Value.Trim(); $c = [IO.File]::ReadAllText('build\app\ui\config'); $c = $c.Replace('{{APP_NAME}}', $appName).Replace('{{SERVICE_PORT}}', $port).Replace('{{DISPLAY_NAME}}', $title); [IO.File]::WriteAllText('build\app\ui\config', $c)"

if exist "input\icons\ICON.PNG" (
    copy "input\icons\ICON.PNG" "build\ICON.PNG" >nul
    copy "input\icons\ICON.PNG" "build\app\ui\images\icon-64.png" >nul
)
if exist "input\icons\ICON_256.PNG" (
    copy "input\icons\ICON_256.PNG" "build\ICON_256.PNG" >nul
    copy "input\icons\ICON_256.PNG" "build\app\ui\images\icon-256.png" >nul
)

echo [..] Converting script line endings to LF...
powershell -NoProfile -Command "Get-ChildItem 'build\cmd' -File | ForEach-Object { $c = [IO.File]::ReadAllText($_.FullName) -replace \"`r`n\", \"`n\"; [IO.File]::WriteAllText($_.FullName, $c) }"

echo [..] Building with fnpack...
cd build
call "%~dp0fnpack" build
if errorlevel 1 (
    echo.
    echo [ERROR] fnpack build failed
    cd ..
    pause
    exit /b 1
)
cd ..

if not exist "output" mkdir "output"
for %%f in (build\*.fpk) do (
    move "%%f" "output\" >nul
    echo.
    echo ==========================================
    echo   SUCCESS!
    echo   Output: output\%%~nxf
    echo ==========================================
)

echo.
pause
