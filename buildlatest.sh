#!/bin/bash
echo "Bygger syfosoknadbrukernotifikasjon for bruk i flex-docker-compose"
./gradlew ktlintFormat
./gradlew shadowJar
docker build -t syfosoknadbrukernotifikasjon:latest .
