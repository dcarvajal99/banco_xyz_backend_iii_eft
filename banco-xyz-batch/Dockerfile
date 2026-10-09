# Imagen de banco-xyz-batch: la migracion de fin_legacy_data con Spring Batch, como contenedor de una sola ejecucion.
# Se construye desde el codigo fuente (multi-etapa): la imagen final trae solo un JRE, la aplicacion y los CSV legacy.
# Construccion aislada: docker build -t banco-xyz/banco-xyz-batch:1.0.0 banco-xyz-batch/  (o desde el docker-compose.yaml).
#
# No es un servicio: arranca, ejecuta el Job que recibe por argumentos, deja el resultado en el esquema "migracion" y
# termina con un codigo de salida (0 bien, 1 fallo tecnico tras las reejecuciones automaticas, 2 parametros, 3 revision
# manual). Los microservicios esperan a que termine bien (depends_on: service_completed_successfully).

FROM maven:3.9-eclipse-temurin-21 AS construccion
WORKDIR /fuente
# 1) Solo el pom: las dependencias quedan en una capa propia y no se vuelven a descargar cuando cambia el codigo.
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline
# 2) El codigo y el jar, separado en las capas de Spring Boot.
COPY src src
RUN mvn -B -q -ntp package -DskipTests \
 && java -Djarmode=tools -jar target/banco-xyz-batch-1.0.0.jar extract --layers --launcher --destination capas

FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="banco-xyz-batch" \
      org.opencontainers.image.description="Banco XYZ - migracion batch de fin_legacy_data (Spring Batch)" \
      org.opencontainers.image.version="1.0.0"
RUN addgroup -S -g 10001 banco && adduser -S -D -H -u 10001 -G banco banco
WORKDIR /app
COPY --from=construccion /fuente/capas/dependencies/ ./
COPY --from=construccion /fuente/capas/spring-boot-loader/ ./
COPY --from=construccion /fuente/capas/snapshot-dependencies/ ./
COPY --from=construccion /fuente/capas/application/ ./
# Los tres archivos de cada semana de fin_legacy_data; la salida (reportes, bitacora, conciliacion) va a un volumen.
COPY data data
RUN mkdir salida && chown banco:banco salida
USER banco
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
CMD ["--job=completa", "--dataset=semana_3", "--salida=salida"]
