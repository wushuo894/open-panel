FROM wushuo894/eclipse-temurin:26-jre-alpine

COPY docker/run.sh /run.sh
COPY docker/exec.sh /exec.sh
COPY open-panel-application/target/open-panel.jar /usr/app/open-panel.jar

WORKDIR /usr/app
VOLUME ["/config"]

ENV PUID=1000 PGID=1000 UMASK=022
ENV JAVA_OPTS="-Xms64m -Xmx512m -Xss256k -XX:+UseG1GC"
ENV SERVER_PORT=7788 OPEN_PANEL_CONFIG_DIR=/config OPEN_PANEL_CONTAINER=true TZ=Asia/Shanghai

EXPOSE ${SERVER_PORT}
RUN sed -i 's/\r//g' /*.sh && chmod +x /*.sh
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/api/public/health" || exit 1
CMD ["/exec.sh"]
