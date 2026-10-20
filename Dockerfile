# Build from the repository root, with the submodule checked out:
#   docker build -t tether-push .
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -q installDist

FROM eclipse-temurin:17-jre
COPY --from=build /src/build/install/tether-push /app
USER 65532
CMD ["/app/bin/tether-push"]
