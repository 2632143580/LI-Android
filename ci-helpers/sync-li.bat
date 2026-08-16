@echo off
REM sync-li.bat - One-click sync LI source updates into the LI-Android phone build.
REM
REM What it does (collapses the manual 5-step sync into one command):
REM   1. Build LI single-file HTML from source  (npm install + npm run build)
REM   2. Copy dist/index.html  ->  LI-Android/app/src/main/assets/index.html
REM   3. Commit + push LI-Android (uses YOUR local git credentials via GCM)
REM
REM Prerequisites:
REM   - Node.js installed and on PATH
REM   - You have push access to the LI-Android repo
REM   - NOTE: the first push also carries the CI workflow change, so your
REM     credentials (or the PAT used by the agent) need the `workflow` scope.
REM
REM Usage:
REM   double-click this file, or run it from a terminal:  sync-li.bat

setlocal
set "LI_DIR=E:\xMe\aifront\LI"
set "AND_DIR=E:\xMe\aifront\LI-Android"
set "ASSET=app\src\main\assets\index.html"

echo [1/3] Building LI single-file HTML from source...
pushd "%LI_DIR%"
call npm install
if errorlevel 1 goto :fail
call npm run build
if errorlevel 1 goto :fail
popd

echo [2/3] Copying dist/index.html -^> LI-Android\%ASSET%...
if not exist "%AND_DIR%\%ASSET%" (
  echo ERROR: asset path not found: %AND_DIR%\%ASSET%
  goto :fail
)
copy /Y "%LI_DIR%\dist\index.html" "%AND_DIR%\%ASSET%"
if errorlevel 1 goto :fail

echo [3/3] Committing and pushing LI-Android...
pushd "%AND_DIR%"
git add "%ASSET%"
git commit -m "sync: update LI asset from local build"
if errorlevel 1 (
  echo (nothing new to commit)
  popd
  goto :end
)
git push
if errorlevel 1 goto :fail
popd

echo.
echo DONE. LI-Android asset updated. GitHub Actions will rebuild the APK automatically.
goto :end

:fail
echo.
echo SYNC FAILED. Read the errors above.
exit /b 1

:end
endlocal
