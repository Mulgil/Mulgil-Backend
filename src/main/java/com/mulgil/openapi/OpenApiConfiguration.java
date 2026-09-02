package com.mulgil.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfiguration {
    private static final Map<String, EndpointDoc> ENDPOINTS = Map.ofEntries(
            doc("POST", "/api/v1/auth/oauth/google", "인증", "Google 로그인", "Google ID token을 검증하고 내부 사용자와 JWT access/refresh token 쌍을 발급합니다.", true),
            doc("POST", "/api/v1/auth/refresh", "인증", "토큰 갱신", "Refresh token family rotation과 재사용 감지를 적용해 새 토큰 쌍을 발급합니다.", true),
            doc("POST", "/api/v1/auth/logout", "인증", "로그아웃", "제출한 refresh token family를 revoke하여 이후 갱신을 차단합니다.", true),
            doc("GET", "/api/v1/courses", "학습 관리", "과목 목록 조회", "인증된 사용자가 소유한 과목만 반환합니다.", false),
            doc("POST", "/api/v1/courses", "학습 관리", "과목 생성", "과목명, 담당자, 학기를 저장하고 소유자 범위의 과목을 생성합니다.", false),
            doc("GET", "/api/v1/timetable/slots", "학습 관리", "시간표 슬롯 조회", "선택한 과목 또는 전체 소유 시간표 슬롯을 조회합니다.", false),
            doc("POST", "/api/v1/timetable/slots", "학습 관리", "시간표 슬롯 생성", "소유한 과목에 ISO 요일과 IANA timezone 기반 시간표 슬롯을 추가합니다.", false),
            doc("PATCH", "/api/v1/timetable/slots/{slotId}", "학습 관리", "시간표 슬롯 수정", "소유한 시간표 슬롯만 수정합니다.", false),
            doc("DELETE", "/api/v1/timetable/slots/{slotId}", "학습 관리", "시간표 슬롯 삭제", "소유한 시간표 슬롯을 삭제합니다.", false),
            doc("GET", "/api/v1/courses/{courseId}/sessions", "학습 관리", "차시 목록 조회", "소유한 과목에 속한 차시만 반환합니다.", false),
            doc("POST", "/api/v1/courses/{courseId}/sessions", "학습 관리", "차시 생성", "같은 과목 안에서 고유한 차시 번호를 가진 차시를 생성합니다.", false),
            doc("GET", "/api/v1/sessions/{sessionId}", "학습 관리", "차시 조회", "소유한 차시의 일정과 학습 범위를 조회합니다.", false),
            doc("GET", "/api/v1/courses/{courseId}/exams", "학습 관리", "시험 목록 조회", "과목에 속한 소유자 범위의 시험과 선택 차시 범위를 조회합니다.", false),
            doc("POST", "/api/v1/courses/{courseId}/exams", "학습 관리", "시험 생성", "중복되지 않고 동일 과목에 속한 차시 범위를 사용해 시험을 생성합니다.", false),
            doc("POST", "/api/v1/sessions/{sessionId}/materials/upload-url", "자료 업로드", "PDF 업로드 URL 발급", "PDF metadata를 검증한 뒤 서버를 거치지 않는 짧은 만료의 signed PUT URL을 발급합니다.", false),
            doc("POST", "/api/v1/materials/{materialId}/upload-complete", "자료 업로드", "PDF 업로드 완료", "GCS metadata와 checksum을 재검증하고 PDF 추출 작업을 비동기로 시작합니다.", false),
            doc("GET", "/api/v1/sessions/{sessionId}/materials", "자료 업로드", "차시 자료 조회", "소유한 차시에 연결된 PDF 자료 metadata만 반환하며 object key는 노출하지 않습니다.", false),
            doc("GET", "/api/v1/materials/{materialId}/download-url", "자료 업로드", "PDF 다운로드 URL 발급", "소유한 자료에 대한 짧은 만료의 signed GET URL을 반환합니다.", false),
            doc("POST", "/api/v1/exams/{examId}/resources", "자료 업로드", "기출 자료 업로드 URL 발급", "사용자가 직접 첨부한 application/pdf 기출 자료의 signed PUT URL을 발급합니다.", false),
            doc("POST", "/api/v1/exam-resources/{examResourceId}/upload-complete", "자료 업로드", "기출 자료 업로드 완료", "기출 PDF checksum을 재검증하고 선택 시험 범위별 색인을 내부적으로 시작합니다.", false),
            doc("GET", "/api/v1/exams/{examId}/resources", "자료 업로드", "기출 자료 조회", "소유한 시험에 연결된 기출 PDF metadata만 반환하며 object key는 노출하지 않습니다.", false),
            doc("GET", "/api/v1/exam-resources/{examResourceId}/download-url", "자료 업로드", "기출 PDF 다운로드 URL 발급", "업로드가 완료된 소유 기출 자료에 대한 짧은 만료의 signed GET URL을 반환합니다.", false),
            doc("POST", "/api/v1/recordings/upload-url", "녹음·STT", "녹음 업로드 URL 발급", "앱 녹음 형식 metadata를 검증하고 signed PUT URL을 발급합니다.", false),
            doc("POST", "/api/v1/recordings/{recordingId}/upload-complete", "녹음·STT", "녹음 업로드 완료", "업로드 파일을 재검증하고 실제 duration과 차시 후보를 반환합니다.", false),
            doc("GET", "/api/v1/jobs/{jobId}", "비동기 작업", "작업 조회", "소유한 비동기 AI 작업의 상태, 재시도 횟수, 안전한 오류 코드를 조회합니다.", false),
            doc("GET", "/api/v1/sessions/{sessionId}/jobs", "비동기 작업", "차시 작업 목록 조회", "소유한 차시에 연결된 PDF, OCR, STT, 생성 작업을 polling용으로 반환합니다.", false),
            doc("POST", "/api/v1/jobs/{jobId}/retry", "비동기 작업", "작업 재시도", "재시도 가능한 실패 작업을 같은 job ID로 queued 상태로 전이합니다.", false),
            doc("GET", "/api/v1/sessions/{sessionId}/notes", "노트·필기", "차시 노트 목록 조회", "소유한 차시에 생성된 typed Markdown 노트 목록을 조회합니다.", false),
            doc("POST", "/api/v1/sessions/{sessionId}/notes", "노트·필기", "노트 생성", "차시에 typed Markdown 노트를 생성합니다.", false),
            doc("GET", "/api/v1/notes/{noteId}", "노트·필기", "노트 단건 조회", "소유한 typed Markdown 노트의 본문과 version을 조회합니다.", false),
            doc("PATCH", "/api/v1/notes/{noteId}", "노트·필기", "노트 수정", "낙관적 version을 확인하고 노트 내용만 저장합니다.", false),
            doc("POST", "/api/v1/notes/{noteId}/leave", "노트·필기", "노트 이탈 처리", "새 version일 때만 문단 색인과 review 생성 의존 작업을 시작하며 중복 이탈은 204를 반환합니다.", false),
            doc("GET", "/api/v1/materials/{materialId}/annotations", "노트·필기", "PDF 필기 조회", "소유한 PDF에 저장된 pen, highlight, emphasis 영역을 반환하며 OCR 결과는 포함하지 않습니다.", false),
            doc("PUT", "/api/v1/materials/{materialId}/annotations", "노트·필기", "PDF 필기 저장", "normalized pen, highlight, emphasis 영역을 낙관적 version으로 저장합니다.", false),
            doc("POST", "/api/v1/materials/{materialId}/annotations/leave", "노트·필기", "PDF 필기 이탈 처리", "변경된 pen 영역의 손글씨 OCR과 review 생성 의존 작업만 비동기로 시작합니다.", false),
            doc("PATCH", "/api/v1/handwriting-blocks/{blockId}/confirm", "노트·필기", "손글씨 인식 확정", "사용자가 보정한 저신뢰도 손글씨를 확정하고 review 생성 작업을 다시 시작합니다.", false),
            doc("POST", "/api/v1/recordings/{recordingId}/confirm-mapping", "녹음·STT", "녹음 차시 확정", "소유한 차시의 3시간 누적 제한을 transaction에서 검증한 뒤 STT 작업을 시작합니다.", false),
            doc("GET", "/api/v1/sessions/{sessionId}/summaries", "생성물", "차시 생성물 조회", "현재 source snapshot을 통과한 preview 또는 review summary와 mindmap을 조회합니다.", false),
            doc("GET", "/api/v1/exams/{examId}/summary", "생성물", "시험 범위 요약 조회", "현재 source snapshot을 통과한 시험 범위 summary를 조회합니다.", false),
            doc("POST", "/api/v1/exams/{examId}/summary/generate", "생성물", "시험 범위 요약 생성", "선택 시험 범위의 현재 source를 근거로 exam summary 생성 작업을 요청합니다.", false),
            doc("GET", "/api/v1/exams/{examId}/predicted-quiz", "생성물", "기출 기반 예상문제 조회", "기출 기반 예상문제를 정답과 해설 없이 조회합니다.", false),
            doc("POST", "/api/v1/exams/{examId}/predicted-quiz/generate", "생성물", "기출 기반 예상문제 생성", "선택 시험 범위의 indexed past_exam 자료를 필수 근거로 예상문제 생성 작업을 요청합니다.", false),
            doc("GET", "/api/v1/sessions/{sessionId}/quiz", "퀴즈·진도", "퀴즈 조회", "연습문제와 기출 기반 문제를 반환하되 정답과 해설은 노출하지 않습니다.", false),
            doc("POST", "/api/v1/quiz/questions/{questionId}/attempts", "퀴즈·진도", "퀴즈 답안 제출", "O/X boolean 또는 4지선다 0..3 index를 채점하고 정답, 해설 sourceRefs, 진도를 반환합니다.", false),
            doc("PUT", "/api/v1/devices/fcm-token", "알림", "FCM 기기 토큰 등록", "Android 또는 iOS 기기 토큰과 timezone을 소유자 범위로 upsert합니다.", false),
            doc("DELETE", "/api/v1/devices/fcm-token", "알림", "FCM 기기 토큰 삭제", "소유한 기기 토큰을 제거합니다.", false),
            doc("GET", "/api/v1/notifications", "알림", "알림 목록 조회", "소유한 알림을 조회하며 unreadOnly로 읽지 않은 알림만 필터링할 수 있습니다.", false)
    );

    @Bean
    OpenAPI mulgilOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Mulgil MVP Backend API").version("v1")
                        .description("Mulgil MVP의 인증, 학습 자료, AI 처리, 생성물, 퀴즈, 알림 API입니다."))
                .tags(List.of(
                        tag("인증", "Google 로그인과 refresh token lifecycle"),
                        tag("학습 관리", "과목, 시간표, 차시, 시험 범위"),
                        tag("자료 업로드", "signed URL 기반 PDF와 기출 자료 업로드"),
                        tag("노트·필기", "typed note, PDF ink/highlight, 손글씨 확정"),
                        tag("녹음·STT", "앱 녹음 업로드, 차시 확정, 한국어 STT"),
                        tag("비동기 작업", "AI 작업 polling과 재시도"),
                        tag("생성물", "source-linked summary, mindmap, 예상문제 생성"),
                        tag("퀴즈·진도", "퀴즈 조회, 제출, immutable attempt와 progress"),
                        tag("알림", "FCM 기기 토큰과 privacy-safe 알림")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer mvpOperationDocumentation() {
        return openApi -> openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                .forEach((method, operation) -> applyDocumentation(method.name(), path, operation)));
    }

    private static void applyDocumentation(String method, String path, io.swagger.v3.oas.models.Operation operation) {
        EndpointDoc endpoint = ENDPOINTS.get(method + " " + path);
        if (endpoint == null) {
            return;
        }
        operation.setTags(List.of(endpoint.tag()));
        operation.setSummary(endpoint.summary());
        operation.setDescription(endpoint.description());
        operation.setSecurity(endpoint.publicEndpoint() ? List.of()
                : List.of(new SecurityRequirement().addList("bearerAuth")));
    }

    private static Map.Entry<String, EndpointDoc> doc(
            String method, String path, String tag, String summary, String description, boolean publicEndpoint
    ) {
        return Map.entry(method + " " + path, new EndpointDoc(tag, summary, description, publicEndpoint));
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private record EndpointDoc(String tag, String summary, String description, boolean publicEndpoint) {}
}
