package tri_lion.health.controller.health;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.domain.health.*;
import tri_lion.health.service.health.*;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final HealthDocumentService documents;
    private final AnalysisService analyses;
    private final ObjectMapper json;

    public HealthController(HealthDocumentService d, AnalysisService a, ObjectMapper j) {
        documents = d;
        analyses = a;
        json = j;
    }

    @PostMapping(value = "/health-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<Object>> upload(
            @RequestPart MultipartFile file,
            @RequestParam String documentType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate measuredAt) {
        var d = documents.upload(file, documentType, measuredAt);
        return ResponseEntity.status(201)
                .body(ApiResponse.success(201, "건강 문서 업로드 성공", document(d)));
    }

    @GetMapping("/health-documents")
    ApiResponse<Object> docs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<HealthDocument> p = documents.list(page, size);
        return ApiResponse.success(200, "건강 문서 목록 조회 성공", paged(p.map(this::document)));
    }

    @DeleteMapping("/health-documents/{id}")
    ApiResponse<Void> delete(@PathVariable Long id) {
        documents.delete(id);
        return ApiResponse.success(200, "건강 문서 삭제 성공", null);
    }

    @PostMapping("/health-analyses")
    ResponseEntity<ApiResponse<Object>> analysis(
            @Valid @RequestBody AnalysisRequest r,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        Analysis a = analyses.create(r.documentIds(), key);
        return ResponseEntity.accepted()
                .body(
                        ApiResponse.success(
                                202,
                                "건강 상태 분석을 시작했습니다.",
                                Map.of(
                                        "analysisId",
                                        a.getId(),
                                        "status",
                                        a.getStatus(),
                                        "createdAt",
                                        a.getCreatedAt())));
    }

    @GetMapping("/health-analyses/{id}")
    ApiResponse<Object> one(@PathVariable Long id) {
        return ApiResponse.success(200, "건강 상태 분석 조회 성공", analysis(analyses.one(id)));
    }

    @GetMapping("/health-analyses/latest")
    ApiResponse<Object> latest() {
        return ApiResponse.success(200, "최신 AI 건강 분석 조회 성공", analysis(analyses.latest()));
    }

    @GetMapping("/health-analyses")
    ApiResponse<Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(
                200,
                "AI 건강 분석 이력 조회 성공",
                paged(analyses.list(status, page, size).map(this::analysis)));
    }

    private Map<String, Object> document(HealthDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", d.getId());
        m.put("documentType", d.getType());
        m.put("fileName", d.getOriginalFileName());
        m.put("measuredAt", d.getMeasuredAt());
        m.put("processingStatus", d.getProcessingStatus());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private Map<String, Object> analysis(Analysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("status", a.getStatus());
        m.put("progress", a.getProgress());
        m.put("summary", a.getSummary());
        m.put("failureReason", a.getFailureReason());
        m.put("modelVersion", a.getModelVersion());
        m.put("promptVersion", a.getPromptVersion());
        if (a.getDetails() != null)
            try {
                m.putAll(json.readValue(a.getDetails(), Map.class));
            } catch (Exception ignored) {
            }
        m.put("completedAt", a.getCompletedAt());
        return m;
    }

    private Map<String, Object> paged(Page<?> p) {
        return Map.of(
                "content",
                p.getContent(),
                "page",
                p.getNumber(),
                "size",
                p.getSize(),
                "totalElements",
                p.getTotalElements(),
                "totalPages",
                p.getTotalPages(),
                "hasNext",
                p.hasNext());
    }

    public record AnalysisRequest(@NotNull List<Long> documentIds) {}
}
