#!/usr/bin/env bash
# Build the fat JAR and create a deployable Beanstalk bundle.
# Run from the backend/ directory.

set -e

echo "Building fat JAR..."
./gradlew clean shadowJar

echo "Creating Beanstalk bundle..."
BUNDLE="build/fueller-backend-bundle.zip"
rm -f "$BUNDLE"

# Beanstalk expects Procfile and .ebextensions at the root of the zip,
# alongside the JAR.
cd build/libs
cp ../../Procfile .
cp -r ../../.ebextensions .
zip -r "../../$BUNDLE" fueller-backend.jar Procfile .ebextensions
rm Procfile
rm -rf .ebextensions
cd ../..

echo ""
echo "Bundle created: $BUNDLE"
echo "Upload this zip to Elastic Beanstalk via the console or:"
echo "  aws elasticbeanstalk create-application-version ..."
