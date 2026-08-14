package tri_lion.health.controller.routine;

import jakarta.validation.Valid;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.domain.health.AiJob;
import tri_lion.health.domain.routine.*;
import tri_lion.health.dto.request.routine.RoutineRequests;
import tri_lion.health.service.routine.RoutineService;

@RestController
@RequestMapping("/api/v1/routines")
public class RoutineController {
    private final RoutineService service;

    public RoutineController(RoutineService s) {
        service = s;
    }

    @PostMapping("/generations")
    ResponseEntity<ApiResponse<Object>> generate(
            @Valid @RequestBody RoutineRequests.GenerationRequest q,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        AiJob j = service.request(q, key);
        return ResponseEntity.accepted()
                .body(
                        ApiResponse.success(
                                202,
                                "맞춤 루틴 생성을 시작했습니다.",
                                Map.of("generationId", j.getId(), "status", j.getStatus())));
    }

    @GetMapping("/generations/{id}")
    ApiResponse<Object> generation(@PathVariable Long id) {
        AiJob j = service.generation(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generationId", j.getId());
        m.put("status", j.getStatus());
        m.put("routineId", j.getResultId());
        return ApiResponse.success(
                200, j.getStatus() == AiJob.Status.COMPLETED ? "맞춤 루틴 생성 완료" : "맞춤 루틴 생성 진행 중", m);
    }

    @GetMapping
    ApiResponse<Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Routine> p = service.list(page, size);
        return ApiResponse.success(
                200,
                "전체 루틴 목록 조회 성공",
                Map.of(
                        "content",
                        p.getContent().stream().map(this::summary).toList(),
                        "page",
                        p.getNumber(),
                        "size",
                        p.getSize(),
                        "totalElements",
                        p.getTotalElements(),
                        "totalPages",
                        p.getTotalPages(),
                        "hasNext",
                        p.hasNext()));
    }

    @GetMapping("/today")
    ApiResponse<Object> today() {
        Routine r = service.today();
        return ApiResponse.success(200, "오늘의 루틴 조회 성공", detail(service.detail(r.getId())));
    }

    @GetMapping("/{id}")
    ApiResponse<Object> one(@PathVariable Long id) {
        return ApiResponse.success(200, "루틴 전체 상세 조회 성공", detail(service.detail(id)));
    }

    @PatchMapping("/{id}")
    ApiResponse<Object> patch(
            @PathVariable Long id, @Valid @RequestBody RoutineRequests.PatchRoutine q) {
        Routine r = service.patch(id, q);
        return ApiResponse.success(
                200,
                "루틴 수정 성공",
                Map.of(
                        "routineId",
                        r.getId(),
                        "title",
                        r.getTitle(),
                        "lastModifiedBy",
                        r.getLastModifiedBy(),
                        "aiAdjustmentAllowed",
                        r.isAiAdjustmentAllowed(),
                        "updatedAt",
                        r.getUpdatedAt()));
    }

    @PostMapping("/{id}/sections/{sectionId}/exercises")
    ResponseEntity<ApiResponse<Object>> add(
            @PathVariable Long id,
            @PathVariable Long sectionId,
            @Valid @RequestBody RoutineRequests.ExerciseRequest q) {
        ExerciseItem i = service.add(id, sectionId, q);
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "운동 항목 추가 성공",
                                Map.of(
                                        "exerciseId",
                                        i.getId(),
                                        "sectionId",
                                        i.getSectionId(),
                                        "order",
                                        i.getSortOrder(),
                                        "editedBy",
                                        i.getEditedBy())));
    }

    @PatchMapping("/{id}/exercises/{exerciseId}")
    ApiResponse<Object> patchItem(
            @PathVariable Long id,
            @PathVariable Long exerciseId,
            @Valid @RequestBody RoutineRequests.PatchExercise q) {
        return ApiResponse.success(
                200, "운동 항목 수정 성공", exercise(service.patchExercise(id, exerciseId, q)));
    }

    @DeleteMapping("/{id}/exercises/{exerciseId}")
    ApiResponse<Void> delete(@PathVariable Long id, @PathVariable Long exerciseId) {
        service.deleteExercise(id, exerciseId);
        return ApiResponse.success(200, "운동 항목 삭제 성공", null);
    }

    @PutMapping("/{id}/sections/{sectionId}/exercise-order")
    ApiResponse<Object> order(
            @PathVariable Long id,
            @PathVariable Long sectionId,
            @Valid @RequestBody RoutineRequests.OrderRequest q) {
        return ApiResponse.success(
                200,
                "운동 순서 변경 성공",
                Map.of(
                        "sectionId",
                        sectionId,
                        "exerciseIds",
                        service.order(id, sectionId, q.exerciseIds()),
                        "updatedAt",
                        Instant.now()));
    }

    @PostMapping("/{id}/adjustments")
    ResponseEntity<ApiResponse<Object>> adjust(
            @PathVariable Long id,
            @Valid @RequestBody RoutineRequests.AdjustmentRequest q,
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        AiJob j = service.adjust(id, q, key);
        return ResponseEntity.accepted()
                .body(
                        ApiResponse.success(
                                202,
                                "루틴 재조정을 시작했습니다.",
                                Map.of(
                                        "generationId",
                                        j.getId(),
                                        "previousRoutineId",
                                        id,
                                        "status",
                                        j.getStatus())));
    }

    private Map<String, Object> summary(Routine r) {
        return Map.of(
                "id",
                r.getId(),
                "title",
                r.getTitle(),
                "type",
                r.getType(),
                "source",
                r.getSource(),
                "status",
                r.getStatus(),
                "startDate",
                r.getStartDate(),
                "endDate",
                r.getEndDate(),
                "lastModifiedBy",
                r.getLastModifiedBy(),
                "updatedAt",
                r.getUpdatedAt());
    }

    private Map<String, Object> detail(RoutineService.Detail d) {
        Routine r = d.routine();
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(summary(r));
        m.put("description", r.getDescription());
        m.put("aiAdjustmentAllowed", r.isAiAdjustmentAllowed());
        m.put(
                "days",
                d.days().stream()
                        .map(
                                x ->
                                        Map.of(
                                                "routineDayId",
                                                x.routineDayId(),
                                                "dayOfWeek",
                                                x.dayOfWeek(),
                                                "week",
                                                x.week(),
                                                "scheduledDate",
                                                x.scheduledDate(),
                                                "estimatedMinutes",
                                                x.estimatedMinutes(),
                                                "sections",
                                                x.sections().stream()
                                                        .map(
                                                                s ->
                                                                        Map.of(
                                                                                "sectionId",
                                                                                s.sectionId(),
                                                                                "sectionType",
                                                                                s.sectionType(),
                                                                                "title",
                                                                                s.title(),
                                                                                "order",
                                                                                s.order(),
                                                                                "exercises",
                                                                                s
                                                                                        .exercises()
                                                                                        .stream()
                                                                                        .map(
                                                                                                this
                                                                                                        ::exercise)
                                                                                        .toList()))
                                                        .toList()))
                        .toList());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<String, Object> exercise(ExerciseItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exerciseId", i.getId());
        m.put("activityType", i.getItemType());
        m.put("order", i.getSortOrder());
        m.put("name", i.getName());
        m.put("content", i.getContent());
        m.put("scheduledAt", i.getScheduledAt());
        m.put("estimatedMinutes", i.getEstimatedMinutes());
        m.put("targetValue", i.getTargetValue());
        m.put("targetUnit", i.getTargetUnit());
        m.put("sets", i.getSets());
        m.put("restSeconds", i.getRestSeconds());
        m.put("videoUrl", i.getVideoUrl());
        m.put("thumbnailUrl", i.getThumbnailUrl());
        m.put("status", i.getStatus());
        m.put("editedBy", i.getEditedBy());
        m.put("excludeFromAiAdjustment", i.isExcludeFromAiAdjustment());
        return m;
    }
}
