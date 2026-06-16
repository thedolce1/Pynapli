FROM eclipse-temurin:17-jre

WORKDIR /app

COPY server/build/install/server/ /app/

RUN groupadd --gid 10001 pynapli \
    && useradd --uid 10001 --gid pynapli --home-dir /app --shell /usr/sbin/nologin pynapli \
    && mkdir -p /app/.pynapli-local-media \
    && chown -R pynapli:pynapli /app

ENV PYNAPLI_ENV=staging

EXPOSE 8080

USER pynapli

ENTRYPOINT ["/app/bin/server"]
