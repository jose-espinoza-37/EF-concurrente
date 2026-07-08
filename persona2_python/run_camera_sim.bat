@echo off
REM Simula una camara IP (celular/tablet) mientras no se tenga hardware real.
REM Uso: run_camera_sim.bat <id> <puerto> [intervalo_segundos]
cd /d %~dp0
set PYTHONPATH=src
if "%~3"=="" (set INTERVALO=2.0) else (set INTERVALO=%~3)
python src\camaras\camara_cliente_movil.py --id %1 --port %2 --intervalo %INTERVALO%
