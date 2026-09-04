# GCS 브라우저 업로드 CORS

프론트엔드는 백엔드가 발급한 V4 signed URL로 PDF와 녹음 파일을 GCS에 직접 `PUT`한다.
따라서 백엔드 API의 CORS와 별도로 GCS 버킷에도 프론트엔드 Origin을 허용해야 한다.

## 적용

버킷 CORS를 변경하는 계정에는 `storage.buckets.get`과 `storage.buckets.update` 권한이 필요하다.
Google Cloud의 `Storage Admin` 역할에는 두 권한이 포함된다.

```bash
gcloud storage buckets update gs://BUCKET_NAME \
  --cors-file=infra/gcs-cors.json
```

`BUCKET_NAME`에는 운영 백엔드의 `GCS_BUCKET` 값을 사용한다.

## 확인

```bash
gcloud storage buckets describe gs://BUCKET_NAME \
  --format="json(cors)"
```

브라우저에서 PDF 업로드를 다시 실행한 뒤 개발자 도구의 Network 탭에서 다음을 확인한다.

- GCS signed URL을 향한 `OPTIONS` 요청이 성공한다.
- 응답에 `Access-Control-Allow-Origin: https://study.mulgil.app`이 포함된다.
- 이어지는 `PUT` 요청이 2xx로 완료된다.
- `POST /api/v1/materials/{materialId}/upload-complete`가 202로 완료된다.

Vercel의 동적 Preview 도메인은 GCS CORS에서 와일드카드 서브도메인으로 등록할 수 없다.
기본 도메인 `https://mulgil-frontend.vercel.app`에서 검증하거나 필요한 Preview Origin을 임시로 추가한다.

## 롤백

기존 CORS 값을 변경 전에 기록해 두고 동일한 명령으로 이전 JSON 파일을 적용한다.
빈 배열 파일을 적용하면 버킷의 CORS 설정이 모두 제거되므로 운영 중에는 사용하지 않는다.
