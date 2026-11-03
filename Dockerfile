# Fast & Lightweight Multi-Stage Dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

COPY target/firmware-upgrade-controller-1.0.0.jar app.jar

USER appuser

ENV JAVA_OPTS="-Xms64m -Xmx256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8000

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

