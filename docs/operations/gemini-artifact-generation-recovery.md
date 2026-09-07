# Gemini 산출물 생성 배포 및 복구

이 절차는 `source-grounded-v4` 계약, 마인드맵 사고 예산 제한, 퀴즈 검증 계약이 포함된 BE 이미지를 운영자가 직접 배포한 뒤 제한된 실패 작업만 복구할 때 사용한다. 배포 또는 재시도 전에 현재 DB 백업과 일반 배포 점검 절차를 완료한다.

## 환경 변수 확인

`VERTEX_GENERATION_MINDMAP_THINKING_BUDGET` 기본값은 `1024`다. 허용 범위는 `0..24576`이며 `VERTEX_GENERATION_MINDMAP_MAX_OUTPUT_TOKENS`보다 작아야 한다. `0`은 사고 비활성화 비교 실험용 값이다. 이 설정은 요청 모델 ID가 정확히 `gemini-2.5-flash`이고 산출물이 마인드맵일 때만 적용된다. 요약, 퀴즈, 다른 benchmark 모델에는 provider 기본 사고 동작이 유지된다.

`compose.yaml`은 다음 값을 backend 컨테이너에 전달한다.

```yaml
VERTEX_GENERATION_MINDMAP_THINKING_BUDGET: ${VERTEX_GENERATION_MINDMAP_THINKING_BUDGET:-1024}
```

실제 credential이나 전체 환경을 출력하지 말고 단일 항목만 확인한다.

```bash
docker compose config | sed -n '/VERTEX_GENERATION_MINDMAP_THINKING_BUDGET:/p'
```

## BE 이미지와 Git SHA 확인

배포할 revision과 이미지에 같은 SHA를 기록한다. 이미지 라벨이 없다는 사실만으로 오래된 배포라고 판단하지 않는다.

```bash
EXPECTED_GIT_SHA=$(git rev-parse HEAD)
docker build \
  --label org.opencontainers.image.revision="$EXPECTED_GIT_SHA" \
  -t "mulgil-backend:$EXPECTED_GIT_SHA" .
BUILT_IMAGE_ID=$(docker image inspect "mulgil-backend:$EXPECTED_GIT_SHA" --format '{{.Id}}')
IMAGE_GIT_SHA=$(docker image inspect "mulgil-backend:$EXPECTED_GIT_SHA" \
  --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
test "$IMAGE_GIT_SHA" = "$EXPECTED_GIT_SHA"
BACKEND_IMAGE="mulgil-backend:$EXPECTED_GIT_SHA" docker compose up -d --no-build backend
RUNNING_IMAGE_ID=$(docker inspect "$(docker compose ps -q backend)" --format '{{.Image}}')
test "$RUNNING_IMAGE_ID" = "$BUILT_IMAGE_ID"
```

배포 기록에는 `EXPECTED_GIT_SHA`, `BUILT_IMAGE_ID`, `RUNNING_IMAGE_ID`를 함께 남긴다. health check와 일반 smoke test가 통과한 뒤 복구를 시작한다.

## Migration과 rollback

`V022__generation_usage_metadata.sql`은 다음 nullable 컬럼만 추가하는 additive migration이다.

- `ai_provider_usage.thoughts_token_count`
- `ai_provider_usage.finish_reason`
- `generation_model_benchmarks.thoughts_token_count`
- `generation_model_benchmarks.finish_reason`

기존 row는 유지되고 새 컬럼은 null일 수 있다. 이전 앱 이미지로 rollback하더라도 V022 컬럼은 남겨 둔다. 컬럼 삭제, migration history 수정, 임의 downgrade SQL을 실행하지 않는다.

## 명시적 실패 작업 복구

배포만으로 과거 실패 작업은 자동 복구되지 않는다. 인증된 기존 API만 사용한다.

```http
POST /api/v1/jobs/{jobId}/retry
Authorization: Bearer <owner access token>
```

허용 대상은 다음 조건을 모두 만족해야 한다.

- 상태가 `failed`이고 `attempt_count < max_attempts`다.
- 요청 사용자가 작업 owner이며 course가 active다.
- 기존 `PROVIDER_OUTPUT_LIMIT` 명시적 재시도 대상이거나, `INVALID_GENERATION_OUTPUT`인 `preview_quiz_generate`, `review_quiz_generate`, `exam_quiz_generate`다.
- 새 `INVALID_GENERATION_OUTPUT` 퀴즈 복구에서는 현재 source snapshot과 input version fence가 유효하다.

`INVALID_GENERATION_OUTPUT`인 summary, mindmap, `target_generate`, 다른 artifact는 새 복구 대상이 아니다. `INVALID_SOURCE_REFERENCES`도 대상이 아니다. 새 `INVALID_GENERATION_OUTPUT` 퀴즈 복구에서 stale 입력은 HTTP 409 `STALE_INPUT`으로 거부된다. 그 밖의 부적격 상태는 HTTP 409 `JOB_NOT_RETRYABLE`, 다른 owner 또는 inactive course는 HTTP 404로 거부된다. 같은 작업의 중복 또는 동시 retry에서는 하나만 HTTP 202를 받을 수 있다. 기존 `PROVIDER_OUTPUT_LIMIT` 재시도 동작은 변경하지 않는다.

과거 v3 퀴즈 작업은 자료 재업로드, 무관한 자료 수정, `cache_fingerprint` 수정 없이 같은 job ID와 input version으로 재시도한다. 현재 handler가 v4 요청을 보내고 성공 결과의 `prompt_version`을 `source-grounded-v4`로 저장한다. 실패 row를 일괄 `queued`로 바꾸거나 DB를 직접 수정하는 blanket 복구는 금지한다.

## 배포 후 제한 관찰

로컬 fake provider 테스트는 실제 Gemini 품질이나 성공률을 입증하지 않는다. 유료 호출과 운영 쓰기는 배포 담당자가 승인하고 실행한다. 비민감한 동일 fixture로 마인드맵 3회, OX와 4지선다를 포함한 퀴즈 3회를 관찰한다.

각 실행에서 요청 `maxOutputTokens`/`thinkingBudget`, `finishReason`, prompt/candidate/thoughts/total tokens, 지연, job status, validation rule/path, 최종 API availability를 기록한다. `thoughtsTokenCount`는 provider metadata의 scalar를 직접 기록한다. metadata가 없으면 null이며 metadata가 있으면서 값이 0이면 실제 0 또는 provider 미보고(`zero-or-unreported`)로 해석한다. 합계와 prompt/candidate 차이로 사고 토큰을 추정하지 않는다. 3회 모두 완전한 JSON 검증과 게시에 성공하고 `MAX_TOKENS`가 없으며 한국어 설명과 기술 용어가 보존되는지 확인한다. 표본 3회 성공을 신뢰성 보장이나 p95로 표현하지 않는다.

1024에서도 `MAX_TOKENS`가 반복되면 실제 `thoughtsTokenCount`와 요청 설정을 근거로 0과 제한 예산을 비교한다. total-prompt-candidate 차액을 사고 토큰으로 단정하지 않는다. 퀴즈 실패가 반복되면 안전하게 기록된 rule/path로 좁히며 원문 응답이나 사용자 콘텐츠를 일반 로그에 추가하지 않는다.
