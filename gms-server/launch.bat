@echo off
@title BeiDou
chcp 65001

rem 4G 内存主机建议：显式堆上限 2G（JDK 默认 MaxRAMPercentage=25 在 4G 机器上只给 ~1G 堆，
rem 开启 bot 环境（!env loadenv）后堆高水位 + 高分配率会引发频繁 GC、CPU 全满）。
.\jdk-21.0.11+10-jre\bin\java.exe -Xms512m -Xmx2g -XX:MaxMetaspaceSize=256m -Dspring.config.location=application.yml -jar BeiDou.jar
pause
