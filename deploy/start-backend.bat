@echo off
cd /d "%~dp0"
if exist set-env.bat call set-env.bat
java -jar app.jar --server.port=9090 --spring.config.additional-location=optional:file:./application-private.yml
