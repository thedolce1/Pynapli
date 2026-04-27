FROM eclipse-temurin:17-jre

WORKDIR /app

COPY server/build/install/server/ /app/

EXPOSE 8080

ENTRYPOINT ["/app/bin/server"]

