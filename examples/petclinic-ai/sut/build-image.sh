#!/usr/bin/env bash
# Builds the PetClinic AI image from the pinned commit with Spring Boot buildpacks.
# Called by `./gradlew :examples:petclinic-ai:buildSutImage`; all inputs come from sut.properties via env.
#
# Idempotent: if the image tag already exists, nothing is done (SUT_FORCE_REBUILD=true to rebuild).
# Black-box (ADR-002): the SUT source is only cloned and checked out, never modified.
set -euo pipefail

: "${SUT_REPOSITORY:?}" "${SUT_COMMIT:?}" "${SUT_IMAGE:?}" "${SUT_BUILDER:?}" "${SUT_RUN_IMAGE:?}" "${SUT_CHECKOUT_DIR:?}"

if [[ "${SUT_FORCE_REBUILD:-false}" != true ]] && docker image inspect "$SUT_IMAGE" >/dev/null 2>&1; then
  echo "SUT image $SUT_IMAGE already present - skipping build (SUT_FORCE_REBUILD=true to rebuild)"
  exit 0
fi

# 1. Source at the pinned commit.
if [[ ! -d "$SUT_CHECKOUT_DIR/.git" ]]; then
  git clone --quiet "$SUT_REPOSITORY" "$SUT_CHECKOUT_DIR"
fi
cd "$SUT_CHECKOUT_DIR"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Local changes in $SUT_CHECKOUT_DIR - refusing to build a modified SUT (ADR-002)." >&2
  exit 1
fi
git cat-file -e "$SUT_COMMIT^{commit}" 2>/dev/null || git fetch --quiet origin
git -c advice.detachedHead=false checkout --quiet "$SUT_COMMIT"

# 2. Talk to the daemon of the active Docker context, but with an EMPTY Docker config:
#    a developer's ~/.docker/config.json (e.g. empty `auths` entries) can break the buildpacks client.
export DOCKER_HOST="${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}')}"
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
echo '{}' > "$DOCKER_CONFIG/config.json"

# 3. Build.
start=$(date +%s)
./mvnw -B spring-boot:build-image -DskipTests \
  -Dspring-boot.build-image.imageName="$SUT_IMAGE" \
  -Dspring-boot.build-image.builder="$SUT_BUILDER" \
  -Dspring-boot.build-image.runImage="$SUT_RUN_IMAGE"
echo "Built $SUT_IMAGE from $SUT_COMMIT in $(( $(date +%s) - start ))s"
