package tri_lion.health.controller.record;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.domain.record.*;
import tri_lion.health.dto.request.record.RecordBatchRequest;
import tri_lion.health.dto.request.record.RecordRequest;
import tri_lion.health.dto.request.record.WaterRecordRequest;
import tri_lion.health.service.record.RecordService;

@RestController
@RequestMapping("/api/v1")
public class RecordController {
    private final RecordService service;

    public RecordController(RecordService s) {
        service = s;
    }

    @PostMapping("/routine-records")
    ResponseEntity<ApiResponse<Object>> create(@Valid @RequestBody RecordRequest q) {
        ActivityRecord r = service.create(q);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("recordId", r.getId());
        data.put("routineItemId", r.getRoutineItemId());
        data.put("activityType", r.getType());
        data.put("recordStatus", r.getStatus());
        data.put("routineItemStatus", r.getRoutineItemId() == null ? "NOT_LINKED" : r.getStatus());
        data.put(
                "coachingStatus",
                "COMPLETED".equals(r.getStatus()) && r.getType() != ActivityType.OTHER
                        ? "PROCESSING"
                        : "NOT_REQUESTED");
        data.put("createdAt", r.getCreatedAt());
        return ResponseEntity.status(201).body(ApiResponse.success(201, "액티비티 기록 등록 성공", data));
    }

    @PostMapping("/routine-records/batch")
    ResponseEntity<ApiResponse<Object>> createBatch(
            @Valid @RequestBody RecordBatchRequest request) {
        var saved = service.createBatch(request);
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "액티비티 기록 일괄 등록 성공",
                                java.util.Map.of(
                                        "recordIds",
                                        saved.stream().map(ActivityRecord::getId).toList(),
                                        "routineItemIds",
                                        saved.stream()
                                                .map(ActivityRecord::getRoutineItemId)
                                                .filter(java.util.Objects::nonNull)
                                                .toList(),
                                        "count",
                                        saved.size())));
    }

    @PutMapping("/routine-records/water")
    ApiResponse<Object> upsertWater(@Valid @RequestBody WaterRecordRequest request) {
        ActivityRecord record = service.upsertWater(request);
        return ApiResponse.success(
                200,
                "물 섭취 기록 저장 성공",
                java.util.Map.of(
                        "recordId", record.getId(),
                        "glasses", request.glasses(),
                        "milliliters", request.glasses() * 250,
                        "recordedAt", record.getPerformedAt()));
    }

    @PostMapping(value = "/routine-records/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<Object>> uploadImage(@RequestPart("image") MultipartFile image) {
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "액티비티 인증 사진 업로드 성공",
                                java.util.Map.of("imageKey", service.uploadImage(image))));
    }

    @GetMapping("/routine-records")
    ApiResponse<Object> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) ActivityType type) {
        return ApiResponse.success(200, "일자별 액티비티 기록 조회 성공", service.list(date, type));
    }

    @GetMapping("/coachings/latest")
    ApiResponse<Coaching> latest() {
        return ApiResponse.success(200, "최신 AI 코칭 조회 성공", service.latest());
    }
}
