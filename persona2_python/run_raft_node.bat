@echo off
REM Levanta el Nodo Raft #2 (Python), usando la config real del cluster.
REM Uso: run_raft_node.bat [selfId] [rutaClusterProperties]
cd /d %~dp0
set PYTHONPATH=src
if "%~1"=="" (set NODE_ID=python1) else (set NODE_ID=%~1)
if "%~2"=="" (set CFG=config\cluster.properties) else (set CFG=%~2)
python src\raft\raft_node_main.py %NODE_ID% %CFG%
