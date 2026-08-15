#!/bin/sh

# cover write
# 4G 内存主机建议：显式堆上限 2G（JDK 默认 MaxRAMPercentage=25 在 4G 机器上只给 ~1G 堆，
# 开启 bot 环境（!env loadenv）后堆高水位 + 高分配率会引发频繁 GC、CPU 全满）。
# 若主机内存更大可自行上调 -Xmx；低于 4G 请下调并配合缩减 bot 规模。
./jdk-21.0.11+10-jre/bin/java -Xms512m -Xmx2g -XX:MaxMetaspaceSize=256m -Dspring.config.location=application.yml -jar BeiDou.jar &
