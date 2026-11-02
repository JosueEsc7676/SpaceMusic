@echo off
chcp 65001 >nul
title Descargar música a SpaceMusic
set "LLAVE=%USERPROFILE%\.ssh\id_ed25519_iaserver"
set "SERVIDOR=admin123@192.168.10.165"
set "OPC=-o ServerAliveInterval=30 -o ServerAliveCountMax=10 -o ConnectTimeout=15"

:menu
echo.
echo ============================================================
echo   Descargar música a SpaceMusic
echo ------------------------------------------------------------
echo   Pega uno o varios enlaces separados por espacio:
echo     Spotify (canción, álbum, playlist o artista)
echo     YouTube, YouTube Music, SoundCloud y otros
echo   o escribe "Artista - Canción".
echo.
echo   Lo que ya está descargado NO se repite.
echo   Si algo falla se reintenta solo, hasta 3 veces.
echo.
echo   C = configurar tu cuenta de Spotify (descargas más rápidas)
echo   Enter sin escribir nada = salir
echo ============================================================
set "ENLACE="
set /p "ENLACE=Enlace o búsqueda: "
if "%ENLACE%"=="" goto fin
if /i "%ENLACE%"=="C" goto spotify

ssh -t %OPC% -i "%LLAVE%" %SERVIDOR% "descargar \"%ENLACE%\""
if errorlevel 2 (
  echo.
  echo Algunas canciones no se pudieron bajar. Puedes pegar el mismo enlace otra vez:
  echo lo que ya se bajó se salta y solo se intentan las que faltan.
) else if errorlevel 1 (
  echo.
  echo No se pudo conectar con el servidor. Revisa que esté encendido.
)
goto menu

:spotify
echo.
echo Credenciales propias de Spotify (gratis): así Spotify no bloquea las descargas.
echo  1. Entra a https://developer.spotify.com/dashboard e inicia sesión con tu cuenta.
echo  2. "Create app": nombre SpaceMusic, Redirect URI http://127.0.0.1:8080, marca "Web API".
echo  3. En la app, abre "Settings" y copia el Client ID y el Client secret.
echo.
set "CID="
set "CSEC="
set /p "CID=Client ID: "
set /p "CSEC=Client secret: "
if "%CID%"=="" goto menu
if "%CSEC%"=="" goto menu
ssh %OPC% -i "%LLAVE%" %SERVIDOR% "mkdir -p ~/.config/spacemusic && printf 'CLIENT_ID=%%s\nCLIENT_SECRET=%%s\n' '%CID%' '%CSEC%' > ~/.config/spacemusic/spotify.env && chmod 600 ~/.config/spacemusic/spotify.env && echo Guardado. Las descargas de Spotify ahora usan tu cuenta."
goto menu

:fin
