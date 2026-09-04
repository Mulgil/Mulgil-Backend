# 물길 MVP API 명세

> 대상: FE·BE 팀원
>
> 데이터 기준: [ERD.md](ERD.md)
>
> 형식: 사람이 읽는 REST API 명세. OpenAPI의 `path → operation → request → response → error` 구조를 Markdown으로 표현한다.

## 1. 공통 계약

| 항목 | 규칙 |
| --- | --- |
| Base URL | `/api/v1` |
| Media type | 요청·응답은 `application/json; charset=utf-8` |
| 인증 | 인증 API를 제외한 모든 요청은 `Authorization: Bearer {accessToken}` 필요 |
| 리소스 권한 | 서버 query에 `owner_id = authenticated_user_id`를 포함한다. 소유하지 않은 ID도 `404 RESOURCE_NOT_FOUND`다. |
| ID·시간 | ID는 UUID 문자열, 시간은 ISO 8601 UTC timestamp |
| 파일 | 서버는 PDF/오디오 본문을 받지 않는다. signed URL → GCS 직접 PUT → `upload-complete` 순서다. |
| 비동기 | job 생성 endpoint는 `202 Accepted`와 `jobId`를 반환한다. 앱은 job endpoint를 polling한다. |
| 생성물 공개 | 최신 input version이고 claim-level `sourceRefs[]` 검증을 통과한 경우만 공개한다. |

### 1.1 운영 상태와 Swagger

| Operation | 인증 | Success response |
| --- | --- | --- |
| `GET /actuator/health` | 없음 | DB 연결이 정상일 때 `200 { "status": "UP" }` |
| `GET /v3/api-docs` | 없음 | `200` OpenAPI JSON |
| `GET /swagger-ui/index.html` | 없음 | `200` Swagger UI |

### 1.2 성공·오류 응답

성공 응답은 endpoint resource JSON 또는 JSON array를 직접 반환한다. 별도 `data` envelope는 쓰지 않는다.

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "Resource not found.",
  "details": { "field": "optional" }
}
```

| Status | `code` | 의미 |
| --- | --- | --- |
| `401` | `UNAUTHENTICATED` | token이 없거나 만료·검증 실패 |
| `404` | `RESOURCE_NOT_FOUND` | resource가 없거나 현재 사용자의 것이 아님 |
| `409` | `STALE_VERSION` | `expectedVersion`/`changedVersion`이 최신 version과 다름 |
| `409` | `INSUFFICIENT_SOURCE_DATA` | 최신 scope에 생성 가능한 유효 source가 없음 |
| `409` | `JOB_NOT_RETRYABLE` | retry 대상이 아니거나 retry 한도를 모두 사용함 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 허용되지 않은 파일 MIME type |
| `422` | `VALIDATION_FAILED` | request field 또는 도메인 validation 실패 |
| `422` | `UPLOAD_LIMIT_EXCEEDED` | PDF/오디오 개별·차시 한도 초과 |
| `429` | `AI_DAILY_LIMIT_REACHED` | demo profile의 일일 AI job 한도 초과 |

### 1.3 공통 객체

#### `SourceRef`

생성물의 각 주장과 퀴즈 answer·explanation이 원문으로 돌아가기 위해 쓰는 참조다. `sourceType`에 맞지 않는 필드는 보내지 않는다.

| Field | Type | 사용 조건 |
| --- | --- | --- |
| `sourceType` | `pdf_text \| handwriting \| note \| transcript \| past_exam \| table` | 항상 필요 |
| `materialId`, `examResourceId` | UUID | PDF 또는 past exam 자료 |
| `pageNumber`, `bboxNorm` | integer, `{x,y,width,height}` | PDF 페이지·영역. bbox 값은 `0..1` |
| `contentBlockId`, `handwritingBlockId` | UUID | 정규화 block 또는 손글씨 block |
| `noteId`, `paragraphOffset` | UUID, integer | typed note 문단 |
| `recordingId`, `transcriptSegmentId`, `startMs`, `endMs` | UUID, UUID, integer, integer | transcript 구간 |

#### `JobAccepted`

```json
{ "jobId": "uuid", "status": "queued" }
```

#### `AiJob`

| Field | Type | 설명 |
| --- | --- | --- |
| `id` | UUID | job ID |
| `type` | enum | `pdf_extract`, `pdf_ocr`, `handwriting_ocr`, `stt`, `chunk_embed`, `preview_generate`, `review_generate`, `exam_summary_generate`, `exam_quiz_generate`, `notification_send` |
| `status` | enum | `created`, `uploaded`, `queued`, `running`, `succeeded`, `failed`, `needs_user_review`, `cancelled`, `outdated` |
| `inputVersion` | integer | job이 대상으로 삼은 입력 version |
| `attemptCount`, `maxAttempts` | integer | 기본 retry 상한은 2 |
| `errorCode` | string or null | 공개 가능한 실패 code만 포함 |
| `createdAt`, `finishedAt` | timestamp | lifecycle 시간 |

## 2. 인증

### `POST /auth/oauth/google`

Google ID token을 서버에서 issuer, audience, expiry, subject까지 검증한 뒤 내부 user와 token pair를 발급한다. 인증은 필요 없다.

| Request body | Required | Response |
| --- | --- | --- |
| `{ "idToken": "Google ID token" }` | `idToken` | `200 AuthTokens`, `401 UNAUTHENTICATED` |

`AuthTokens`는 `{ accessToken, refreshToken, tokenType: "Bearer", accessExpiresAt, user }`다. `user`는 `{ id, email, displayName }`다. refresh token 원문은 이 응답에서만 전달하고 DB에는 hash만 저장한다.

### `POST /auth/refresh`

| Request body | Response |
| --- | --- |
| `{ "refreshToken": "string" }` | `200 AuthTokens`, `401 UNAUTHENTICATED` |

사용된 refresh token은 revoke하고 새 token pair를 발급한다. reuse 감지 시 같은 family 전체를 revoke한다.

### `POST /auth/logout`

| Request body | Response |
| --- | --- |
| `{ "refreshToken": "string" }` | `204 No Content`, `401 UNAUTHENTICATED` |

해당 refresh token family를 revoke한다.

## 3. 과목·시간표·차시·시험

### 3.1 과목

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /courses` | 없음 | `200 Course[]` | `401` |
| `POST /courses` | `CourseCreateRequest` | `201 Course` | `401`, `422` |
| `PATCH /courses/{courseId}` | `CourseUpdateRequest` | `200 Course` | `401`, `404`, `422` |
| `DELETE /courses/{courseId}` | 없음 | `204` | `401`, `404` |

`CourseCreateRequest`은 `name`(필수, 1~100자), `instructor`(선택, 최대 100자), `term`(선택, 최대 50자)로 구성한다. `Course`는 여기에 `id`, `createdAt`, `updatedAt`을 더한다.

`CourseUpdateRequest`은 같은 세 필드를 갱신한다. `DELETE /courses/{courseId}`는 `deleted_at`을 기록하는 soft delete이며, 과목을 목록·시간표·차시 조회에서 숨긴다. 연결된 슬롯, 차시, 시험, 자료와 GCS 객체는 삭제하지 않는다.

### 3.2 시간표 슬롯

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /timetable/slots?courseId={uuid}` | `courseId` optional query | `200 TimetableSlot[]` | `401` |
| `POST /timetable/slots` | `TimetableSlotWriteRequest` | `201 TimetableSlot` | `404`, `422` |
| `PATCH /timetable/slots/{slotId}` | `TimetableSlotWriteRequest` | `200 TimetableSlot` | `404`, `422` |
| `DELETE /timetable/slots/{slotId}` | 없음 | `204` | `404` |

`TimetableSlotWriteRequest`:

| Field | Type | Required | 제약 |
| --- | --- | --- | --- |
| `courseId` | UUID | Yes | 현재 사용자의 course |
| `weekday` | integer | Yes | ISO Monday=1 ~ Sunday=7 |
| `startTime`, `endTime` | `HH:mm` | Yes | `startTime < endTime` |
| `timezone` | string | Yes | IANA timezone, 예: `Asia/Seoul` |

### 3.3 차시

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /courses/{courseId}/sessions` | 없음 | `200 ClassSession[]` | `404` |
| `POST /courses/{courseId}/sessions` | `ClassSessionCreateRequest` | `201 ClassSession` | `404`, `422` |
| `GET /sessions/{sessionId}` | 없음 | `200 ClassSession` | `404` |

`ClassSessionCreateRequest`은 `sessionNumber`(1 이상), `title`(1~200자), `sessionDate`(date)를 필수로 받고 `startsAt`, `endsAt`은 선택이다. 같은 course에서 `sessionNumber`는 unique다.

### 3.4 시험과 범위

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /courses/{courseId}/exams` | 없음 | `200 Exam[]` | `404` |
| `POST /courses/{courseId}/exams` | `ExamCreateRequest` | `201 Exam` | `404`, `422` |
| `POST /exams/{examId}/resources` | `ExamResourceUploadRequest` | `201 UploadUrl` | `404`, `415`, `422` |
| `POST /exam-resources/{examResourceId}/upload-complete` | `UploadCompleteRequest` | `200 ExamResource` | `404`, `415`, `422` |

```json
{
  "title": "중간고사",
  "examAt": "2026-09-02T10:00:00Z",
  "sessionIds": ["uuid", "uuid"]
}
```

`sessionIds`는 하나 이상이며 중복될 수 없다. 모든 session은 같은 owner·course scope에 있어야 한다. `ExamResourceUploadRequest`는 `{ filename, mimeType: "application/pdf", byteSize }`이며, 현재 사용자가 직접 첨부한 PDF만 등록한다.

## 4. 자료와 signed URL 업로드

### 4.1 업로드 프로토콜

1. 클라이언트가 upload URL endpoint에 파일 metadata만 보낸다.
2. 서버가 owner/scope/MIME/파일 한도를 검사하고 resource row와 짧은 만료의 GCS PUT URL을 만든다.
3. 클라이언트가 URL로 직접 PUT한다.
4. 클라이언트가 `upload-complete`에 checksum을 보낸다.
5. 서버가 GCS metadata, MIME, size, checksum을 재검증한다. PDF는 처리 job, recording은 후보 차시 계산을 시작한다.

`UploadUrl` 응답:

```json
{
  "id": "uuid",
  "uploadUrl": "https://storage.googleapis.com/...",
  "expiresAt": "2026-09-02T10:10:00Z",
  "requiredHeaders": { "Content-Type": "application/pdf" }
}
```

`UploadCompleteRequest`는 `{ "checksumSha256": "64-character-hex" }`다.

### 4.2 PDF 자료

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `POST /sessions/{sessionId}/materials/upload-url` | `MaterialUploadRequest` | `201 UploadUrl` | `404`, `415`, `422` |
| `POST /materials/{materialId}/upload-complete` | `UploadCompleteRequest` | `202 JobAccepted` | `404`, `415`, `422` |
| `GET /sessions/{sessionId}/materials` | 없음 | `200 Material[]` | `404` |
| `GET /materials/{materialId}/download-url` | 없음 | `200 DownloadUrl` | `404` |

`MaterialUploadRequest`:

| Field | Type | Required | 제약 |
| --- | --- | --- | --- |
| `filename` | string | Yes | 1~255자 |
| `mimeType` | string | Yes | `application/pdf`만 |
| `byteSize` | integer | Yes | 1~52,428,800 (50 MB) |
| `sourcePhase` | enum | Yes | `preview_pdf` 또는 `review_pdf` |

`Material`은 `{ id, sessionId, filename, mimeType, byteSize, pageCount, sourcePhase, version, status }`다. `pageCount`는 최대 150, session당 PDF는 최대 5개다. 업로드 URL이 만료된 `created` 예약은 제한에서 제외되며 완료 요청에는 `410 UPLOAD_URL_EXPIRED`를 반환한다. raw object key는 절대 반환하지 않는다. `DownloadUrl`은 `{ downloadUrl, expiresAt }`다.

## 5. typed note와 PDF annotation

### 5.1 typed note

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `POST /sessions/{sessionId}/notes` | `{ bodyMarkdown?: string }` | `201 Note` | `404`, `422` |
| `PATCH /notes/{noteId}` | `NotePatchRequest` | `200 Note` | `404`, `409`, `422` |
| `POST /notes/{noteId}/leave` | `{ changedVersion: integer }` | `202 JobAccepted` or `204` | `404`, `409` |

`NotePatchRequest`:

```json
{ "bodyMarkdown": "# 1주차 정리", "expectedVersion": 3 }
```

`PATCH`는 note 저장과 version 증가만 수행한다. 화면 이탈을 뜻하는 `leave`가 새 version일 때만 note 문단 block/chunk와 review generation dependency를 만든다. 이미 leave 처리한 version 또는 변경 없는 note는 `204`다.

### 5.2 annotation 저장과 이탈

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `PUT /materials/{materialId}/annotations` | `AnnotationWriteRequest` | `200 AnnotationDocument` | `404`, `409`, `422` |
| `POST /materials/{materialId}/annotations/leave` | `{ changedVersion: integer }` | `202 JobAccepted` or `204` | `404`, `409` |
| `PATCH /handwriting-blocks/{handwritingBlockId}/confirm` | `{ confirmedText: string }` | `202 JobAccepted` | `404`, `422` |

`AnnotationWriteRequest`:

| Field | Type | Required | 설명 |
| --- | --- | --- | --- |
| `expectedVersion` | integer | Yes | 현재 annotation version. 새 document의 첫 저장은 `0` |
| `inkStrokes` | `InkStroke[]` | Yes | pen/highlight의 전체 또는 서버와 합의한 diff 표현 |
| `emphasisRegions` | `EmphasisRegion[]` | Yes | 사용자가 드래그해 만든 강조 영역 |

`InkStroke`는 `id`, `pageNumber`, `tool`(`pen`/`highlight`), `color`, `widthNorm`, `points[]`, `bboxNorm`을 가진다. point와 bbox 좌표는 PDF 페이지 기준 `0..1` normalized value만 허용한다.

`EmphasisRegion`은 `id`, `pageNumber`, `bboxNorm`, `tapCount`를 가진다. `tapCount`는 0 이상이며 viewer는 `min(tapCount, 3)`으로 별을 표시한다.

annotation leave에서 서버는 dirty pen stroke들의 union bbox만 OCR input으로 만든다. highlight는 OCR하지 않고 underlying PDF text block의 retrieval weight만 높인다. OCR confidence가 0.80 미만이면 `needs_user_review`이며 `confirm` 전에는 AI 입력으로 사용하지 않는다.

## 6. 오디오 업로드·차시 확정·STT

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `POST /recordings/upload-url` | `RecordingUploadRequest` | `201 UploadUrl` | `415`, `422` |
| `POST /recordings/{recordingId}/upload-complete` | `UploadCompleteRequest` | `200 RecordingUploadComplete` | `404`, `415`, `422` |
| `POST /recordings/{recordingId}/confirm-mapping` | `{ sessionId }` | `202 JobAccepted` | `404`, `409`, `422` |

`RecordingUploadRequest`은 `{ filename, mimeType, byteSize, startedAt }`다. `mimeType`은 `audio/m4a` 또는 `audio/mp4`만 허용한다. `startedAt`은 차시 후보 추천용이며 client duration은 신뢰하지 않는다.

```json
{
  "recordingId": "uuid",
  "durationSeconds": 3600,
  "candidateSessions": [
    { "sessionId": "uuid", "title": "3주차", "overlapScore": 0.92 }
  ]
}
```

서버는 실제 container metadata로 duration을 probe한다. 파일 하나가 3시간을 넘거나 mapping 확정 transaction에서 해당 session의 active recording 합계가 3시간을 넘으면 `422 UPLOAD_LIMIT_EXCEEDED`다. 확정 전에는 STT·chunk·review job을 만들지 않는다.

## 7. job과 생성물

### 7.1 job 조회와 재시도

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /jobs/{jobId}` | 없음 | `200 AiJob` | `404` |
| `GET /sessions/{sessionId}/jobs` | 없음 | `200 AiJob[]` | `404` |
| `POST /jobs/{jobId}/retry` | 없음 | `202 AiJob` | `404`, `409 JOB_NOT_RETRYABLE` |

재시도는 새 job row가 아니라 같은 job ID의 상태를 `failed → queued`로 전이한다. `attemptCount <= maxAttempts`를 지키며 stale input version의 job은 재공개할 수 없다.

### 7.2 차시 summary와 mindmap

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /sessions/{sessionId}/summaries?type=preview\|review` | `type` optional query | `200 SessionGeneration` | `404`, `409 INSUFFICIENT_SOURCE_DATA` |
| `GET /exams/{examId}/summary` | 없음 | `200 ExamGeneration` | `404 EXAM_NOT_FOUND`, `404 GENERATION_NOT_FOUND`, `409 INSUFFICIENT_SOURCE_DATA` |
| `POST /exams/{examId}/summary/generate` | 없음 | `202 JobAccepted` | `404`, `409`, `429` |

```json
{
  "summary": {
    "id": "uuid",
    "type": "review",
    "inputVersion": 4,
    "items": [
      { "text": "핵심 개념", "sourceRefs": [{ "sourceType": "note", "noteId": "uuid", "paragraphOffset": 0 }] }
    ]
  },
  "mindmap": {
    "id": "uuid",
    "inputVersion": 4,
    "nodes": [{ "id": "n1", "label": "핵심 개념", "sourceRefs": [{ "sourceType": "pdf_text", "materialId": "uuid", "pageNumber": 3 }] }],
    "edges": [{ "from": "n1", "to": "n2" }]
  }
}
```

시험 summary 조회 응답인 `ExamGeneration`은 session 응답처럼 wrapper를 사용하지 않으며 다음 형태다. `type`은 항상 `exam`이다.

```json
{
  "id": "uuid",
  "type": "exam",
  "inputVersion": 4,
  "items": [
    { "text": "시험 핵심 개념", "sourceRefs": [{ "sourceType": "note", "noteId": "uuid", "paragraphOffset": 0 }] }
  ],
  "tables": []
}
```

다른 사용자의 시험은 존재 여부를 숨기고 `404 EXAM_NOT_FOUND`를 반환한다. 현재 source가 준비되지 않았으면 `409 INSUFFICIENT_SOURCE_DATA`, source는 준비됐지만 생성물이 없으면 `404 GENERATION_NOT_FOUND`다.

summary의 `items[]`, 표의 각 factual cell, mindmap의 모든 정보 node는 각각 non-empty `sourceRefs[]`를 가져야 한다. 최상위 metadata로 claim 근거를 대체하면 공개 실패다.

## 8. 퀴즈와 progress

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `GET /sessions/{sessionId}/quiz` | 없음 | `200 QuizQuestion[]` | `404`, `409` |
| `GET /exams/{examId}/predicted-quiz` | 없음 | `200 QuizQuestion[]` | `404 EXAM_NOT_FOUND`, `409 QUIZ_NOT_READY` |
| `POST /exams/{examId}/predicted-quiz/generate` | 없음 | `202 JobAccepted` | `404`, `409`, `429` |
| `POST /quiz/questions/{questionId}/attempts` | `{ answer }` | `201 QuizAttemptResult` | `404 QUIZ_NOT_FOUND`, `422 VALIDATION_FAILED`, `422 QUIZ_INVALID` |

`QuizQuestion`은 `id`, `type`, `prompt`, `options?`, `sourceRefs`를 가진다. `type`은 `true_false` 또는 `multiple_choice`만 허용하며, multiple choice는 정확히 4개의 `options`를 가진다. session quiz와 exam predicted-quiz의 공개 조회 응답에는 정답(`answer`)과 해설(`explanation`)을 포함하지 않는다. 다른 사용자의 exam predicted-quiz는 `404 EXAM_NOT_FOUND`, 생성물이 준비되지 않았으면 `409 QUIZ_NOT_READY`다.

답안 request의 `answer`는 true/false에서는 boolean, 4지선다에서는 `0..3` index다.

```json
{
  "attemptId": "uuid",
  "isCorrect": true,
  "answer": { "value": true, "sourceRefs": [{ "sourceType": "pdf_text", "materialId": "uuid", "pageNumber": 3 }] },
  "explanation": { "text": "해설", "sourceRefs": [{ "sourceType": "pdf_text", "materialId": "uuid", "pageNumber": 3 }] },
  "progress": { "scopeType": "exam", "scopeId": "uuid", "correctCount": 3, "incorrectCount": 1, "lastAttemptAt": "2026-09-02T10:00:00Z", "updatedAt": "2026-09-02T10:00:00Z" }
}
```

attempt는 session practice 문제와 exam predicted-quiz 문제 모두에 사용한다. `progress`는 `scopeType`과 `scopeId`로 범위를 구분한다. session 문제는 `scopeType: "session"`과 session ID, 예상문제는 `scopeType: "exam"`과 exam ID를 반환하며 `sessionId`·`examId` 필드를 별도로 섞지 않는다. `correctCount`와 `incorrectCount`는 해당 scope의 누적값이다. 채점 응답에는 정답과 해설이 포함될 수 있다. 다른 사용자 소유이거나 `succeeded` 상태가 아닌 문제는 존재를 숨기고 `404 QUIZ_NOT_FOUND`, answer type/range가 잘못되면 `422 VALIDATION_FAILED`, 저장된 정답이 유효하지 않으면 `422 QUIZ_INVALID`다.

`predicted-quiz/generate`는 시험 범위와 사용자가 해당 시험에 첨부한 `past_exam` resource만 근거로 사용한다. 실제 기출 제공이나 적중 보장이 아니다.

## 9. FCM token과 notification

| Operation | Request | Success response | Errors |
| --- | --- | --- | --- |
| `PUT /devices/fcm-token` | `DeviceTokenWriteRequest` | `200 DeviceToken` | `422` |
| `DELETE /devices/fcm-token` | `{ token }` | `204` | `401` |
| `GET /notifications?unreadOnly={boolean}` | query optional | `200 Notification[]` | `401` |

`DeviceTokenWriteRequest`:

```json
{ "token": "fcm-token", "platform": "android", "timezone": "Asia/Seoul" }
```

`platform`은 `android` 또는 `ios`다. MVP 완료 검증은 Android actual FCM에 두며 iOS는 token·permission·deep link 준비 범위다.

| Notification field | Type | 설명 |
| --- | --- | --- |
| `id` | UUID | notification row ID |
| `type` | enum | `post_class_reminder`, `processing_complete`, `exam_reminder` |
| `title`, `body` | string | 사용자에게 보이는 문구 |
| `deepLink` | string | 예: `mulgil://sessions/{sessionId}/summary/review` |
| `status` | enum | `scheduled`, `sent`, `failed`, `cancelled` |
| `scheduledAt`, `sentAt` | timestamp | 예정/실제 전송 시각 |

notification payload에는 source text, transcript, signed URL을 넣지 않는다. destination resource ID와 `deepLink`만 넣는다.

## 10. 구현 확인 체크리스트

- 모든 path parameter resource가 owner-scoped query로 읽히는가?
- 파일 API가 server upload proxy 없이 signed URL 완료 흐름을 따르는가?
- note/annotation `leave`가 새 version에서만 job을 만들고 중복 호출은 `204`인가?
- low-confidence handwriting이 confirm 전 generation input에 포함되지 않는가?
- recording mapping에서 transaction과 session row lock으로 3시간 합계를 보장하는가?
- 공개 summary/mindmap/quiz의 claim-level `sourceRefs[]`를 서버가 검증하는가?
- `test` profile이 실제 Google provider 호출 없이 이 명세의 오류와 lifecycle을 재현하는가?
