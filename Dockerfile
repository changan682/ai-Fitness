# ==================== Java BFF 生产镜像 ====================
#
# 构建前提（镜像里不跑 Maven，避免把整个 m2 仓库塞进构建上下文）：
#     mvn -DskipTests package
# 产物路径与 pom.xml 的 artifactId/version 对应：target/fitness-server-1.0.0.jar
#
# ⚠️ 与规范示例的两处差异（都是有意为之）：
# 1. 基础镜像用 eclipse-temurin 而不是 openjdk：Docker 官方的 openjdk 镜像已停止维护，
#    temurin 是其继任者（Adoptium 构建），长期有安全更新。
# 2. jar 文件名用 fitness-server-1.0.0.jar（本项目 artifactId=fitness-server），
#    规范示例写的 fitness-app-1.0.0.jar 与 pom.xml 不符，照抄会 COPY 失败。
FROM eclipse-temurin:17-jre

WORKDIR /app

# 时区与字符集：日志时间戳、中文报错都依赖它们。不设置会按 UTC 输出，
# 与 MySQL 的 serverTimezone=Asia/Shanghai 不一致。
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY target/fitness-server-1.0.0.jar app.jar

EXPOSE 8080

# 用 sh -c 让 $JAVA_OPTS 参与展开（exec 形式不会做变量替换）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
