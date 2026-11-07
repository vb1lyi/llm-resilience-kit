#!/usr/bin/env bash
# TEMPORARY (Stage 1 spike, Q10): run PetClinic AI against MockServer by hand.
# Usage: spike/run-sut.sh up [h2|postgres] | down
set -euo pipefail

NET=llmrk-spike
SUT_IMAGE=llm-resilience/petclinic-ai:d48e749
MOCK_IMAGE=mockserver/mockserver:9.2.0
DUMMY_KEY=sk-dummy-not-a-real-key

case "${1:-}" in
up)
  db="${2:-h2}"
  docker network create "$NET" >/dev/null 2>&1 || true
  docker run -d --rm --name llm-mock --network "$NET" --network-alias llm-mock -p 1080:1080 "$MOCK_IMAGE" >/dev/null

  db_env=()
  if [[ "$db" == postgres ]]; then
    docker run -d --rm --name sut-db --network "$NET" -p 5432:5432 \
      -e POSTGRES_USER=petclinic -e POSTGRES_PASSWORD=petclinic -e POSTGRES_DB=petclinic postgres:18.1 >/dev/null
    until docker exec sut-db pg_isready -U petclinic >/dev/null 2>&1; do sleep 1; done
    db_env=(-e SPRING_PROFILES_ACTIVE=postgres -e POSTGRES_URL=jdbc:postgresql://sut-db:5432/petclinic)
  fi

  docker run -d --rm --name sut --network "$NET" -p 8080:8080 \
    -e SPRING_AI_OPENAI_BASE_URL=http://llm-mock:1080 \
    -e OPENAI_API_KEY="$DUMMY_KEY" \
    "${db_env[@]}" "$SUT_IMAGE" >/dev/null

  start=$(date +%s)
  until curl -fs localhost:8080/actuator/health >/dev/null 2>&1; do
    sleep 1
    if (( $(date +%s) - start > 180 )); then echo "SUT not healthy after 180s"; docker logs sut | tail -40; exit 1; fi
  done
  echo "SUT healthy after $(( $(date +%s) - start ))s (db=$db)"
  ;;
down)
  docker rm -f sut sut-db llm-mock >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  echo "stopped"
  ;;
*)
  echo "usage: $0 up [h2|postgres] | down"; exit 2 ;;
esac
