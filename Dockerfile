FROM gcr.io/distroless/java21-debian12@sha256:225ee1229afb2a79ec5402c10400befab892a03c6534390830a65e2b3d791c34

ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseParallelGC -XX:ActiveProcessorCount=2"

COPY build/libs/app.jar /app/
WORKDIR /app
CMD ["app.jar"]
