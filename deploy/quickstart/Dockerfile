# The quickstart's node image (docs/QUICKSTART.md, docker-compose.yml service `rail8`). It copies what the host built —
#   mvn -B -ntp -q package -DskipTests -pl :rail8-runtime -am
# — the runtime jar (its manifest names Main and a lib/ class path) and target/lib. Build context: the repository root;
# Dockerfile.dockerignore keeps the context to those files.
FROM eclipse-temurin:25.0.4.1_1-jre-noble

RUN useradd --system --user-group --uid 10001 --create-home --home-dir /var/lib/rail8 rail8

WORKDIR /opt/rail8
COPY platform/runtime/target/lib/ lib/
COPY platform/runtime/target/rail8-runtime-*.jar ./
# exactly one runtime jar, under a name that does not carry the version
RUN set -- rail8-runtime-*.jar && [ "$#" -eq 1 ] && [ -f "$1" ] && mv "$1" rail8-runtime.jar

USER rail8
ENV RAIL8_DATA_DIR=/var/lib/rail8
# 8080: the demo's http-in; 8081: the admin API and /health
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/opt/rail8/rail8-runtime.jar"]
