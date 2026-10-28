# The quickstart's node image (docs/QUICKSTART.md, docker-compose.yml service `flottila`). It copies what the host built —
#   mvn -B -ntp -q package -DskipTests -pl flottila-runtime -am
# — the runtime jar (its manifest names Main and a lib/ class path) and target/lib. Build context: the repository root;
# Dockerfile.dockerignore keeps the context to those files.
FROM eclipse-temurin:25.0.4.1_1-jre-noble

RUN useradd --system --user-group --uid 10001 --create-home --home-dir /var/lib/flottila flottila

WORKDIR /opt/flottila
COPY flottila-runtime/target/lib/ lib/
COPY flottila-runtime/target/flottila-runtime-*.jar ./
# exactly one runtime jar, under a name that does not carry the version
RUN set -- flottila-runtime-*.jar && [ "$#" -eq 1 ] && [ -f "$1" ] && mv "$1" flottila-runtime.jar

USER flottila
ENV FLOTTILA_DATA_DIR=/var/lib/flottila
# 8080: the demo's http-in; 8081: the admin API and /health
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/opt/flottila/flottila-runtime.jar"]
