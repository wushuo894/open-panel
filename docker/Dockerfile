FROM wushuo894/eclipse-temurin:26-jre-alpine

COPY docker/run.sh /run.sh
COPY docker/exec.sh /exec.sh
COPY open-panel-application/target/open-panel.jar /usr/app/open-panel.jar

WORKDIR /usr/app
VOLUME ["/config"]

ENV PUID=0 PGID=0 UMASK=022
ENV JAVA_OPTS="-Xms64m -Xmx512m -Xss256k -XX:+UseG1GC"
ENV SERVER_PORT=57788 OPEN_PANEL_CONFIG_DIR=/config TZ=Asia/Shanghai

EXPOSE ${SERVER_PORT}
RUN sed -i 's/\r//g' /*.sh && chmod +x /*.sh
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/api/public/health" || exit 1
CMD ["/exec.sh"]
