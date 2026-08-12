package tri_lion.health.controller.record;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.domain.record.*;
import tri_lion.health.dto.request.record.RecordRequest;
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
        data.put("recordStatus", "SAVED");
        data.put("routineItemStatus", r.getRoutineItemId() == null ? "NOT_LINKED" : "COMPLETED");
        data.put("coachingStatus", "PROCESSING");
        data.put("createdAt", r.getCreatedAt());
        return ResponseEntity.status(201).body(ApiResponse.success(201, "액티비티 기록 등록 성공", data));
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
