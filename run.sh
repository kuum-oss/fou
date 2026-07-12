#!/usr/bin/env bash
# Helper script to run NotifyHub with Java 21 (required — Java 25 is incompatible with Lombok)
set -euo pipefail

JAVA21_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"

if [ ! -d "$JAVA21_HOME" ]; then
  echo "ERROR: Java 21 not found at $JAVA21_HOME"
  echo "Please install Eclipse Temurin 21: https://adoptium.net/"
  exit 1
fi

export JAVA_HOME="$JAVA21_HOME"
echo "Using JAVA_HOME=$JAVA_HOME"

case "${1:-help}" in
  compile)
    ./mvnw compile
    ;;
  test)
    ./mvnw test
    ;;
  package)
    ./mvnw package -DskipTests
    ;;
  run)
    ./mvnw spring-boot:run
    ;;
  *)
    echo "Usage: $0 {compile|test|package|run}"
    echo ""
    echo "  compile  — compile main sources"
    echo "  test     — run integration tests (requires Docker)"
    echo "  package  — build the JAR"
    echo "  run      — run the application locally"
    exit 0
    ;;
esac
