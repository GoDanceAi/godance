#!/usr/bin/env bash
# Regenerates the configuration reference in docs/self-hosting.md from the
# code: every @ConfigurationProperties field, every ${...} placeholder in
# application.yml / application-prod.yml, and every @Value key.
#
#   backend/scripts/config-reference.sh
#
# Each setting needs a line in
# backend/src/test/resources/config-reference/descriptions.txt first; the
# run fails, listing the ones that have none. Needs Java 21 and Maven, not
# Docker. ConfigurationReferenceTest checks the same section in every
# `mvn verify`, so CI fails while the guide is out of date.
set -euo pipefail
cd "$(dirname "$0")/.."
CONFIG_REFERENCE_WRITE=1 mvn -B -q test \
  -Dtest=ConfigurationReferenceTest -Dsurefire.failIfNoSpecifiedTests=false
echo "Regenerated the configuration reference in docs/self-hosting.md."
