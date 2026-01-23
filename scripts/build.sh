#!/bin/bash

# Build script for HFT Trading Platform
set -e

echo "========================================="
echo "Building HFT Trading Platform"
echo "========================================="

# Check Java version
java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$java_version" -lt 17 ]; then
    echo "Error: Java 17 or higher is required"
    exit 1
fi
echo "Java version: $java_version"

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is not installed"
    exit 1
fi
echo "Maven version: $(mvn -version | head -n 1)"

# Clean and build
echo ""
echo "Building project..."
mvn clean package -DskipTests

echo ""
echo "========================================="
echo "Build completed successfully!"
echo "========================================="
echo ""
echo "To run the application:"
echo "  java -jar hft-app/target/hft-app-1.0.0-SNAPSHOT.jar"
echo ""
echo "To run with Docker:"
echo "  docker-compose up -d"
