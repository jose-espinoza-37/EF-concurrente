@echo off
REM Levanta una camara REAL (webcam o stream de celular), usando cv2 SOLO
REM para capturar/decodificar video -- el protocolo de red sigue siendo
REM el mismo socket hecho a mano (ver camaras/camara_cliente_real.py).
REM Uso: run_camera_real.bat <id> <puerto> <fuente> [intervalo_segundos]
REM Ejemplos:
REM   run_camera_real.bat 1 7101 0
REM   run_camera_real.bat 2 7102 http://192.168.1.15:8080/video
cd /d %~dp0
set PYTHONPATH=src
if "%~4"=="" (set INTERVALO=1.0) else (set INTERVALO=%~4)
python src\camaras\camara_cliente_real.py --id %1 --port %2 --source %3 --intervalo %INTERVALO%
