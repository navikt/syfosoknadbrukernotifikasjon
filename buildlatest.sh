#!/bin/bash
echo "Bygger syfosoknadbrukernotifikasjon for bruk i flex-docker-compose"
rm -rf ./build
./gradlew bootJar
docker build -t syfosoknadbrukernotifikasjon:latest .
