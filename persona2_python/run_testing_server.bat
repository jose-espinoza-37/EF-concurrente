@echo off
REM Levanta el Servidor de Testeo de Objetos (camaras + inferencia + Raft client).
cd /d %~dp0
set PYTHONPATH=src
if "%~1"=="" (set CFG=config\testing.properties) else (set CFG=%~1)
python src\testeo\main.py %CFG%
