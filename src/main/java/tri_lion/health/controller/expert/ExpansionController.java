package tri_lion.health.controller.expert;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.dto.request.expert.ExpertContentRequest;
import tri_lion.health.service.expert.ExpansionService;

@RestController
@RequestMapping("/api/v1")
public class ExpansionController {
    private final ExpansionService service;

    public ExpansionController(ExpansionService s) {
        service = s;
    }

    @PostMapping(value = "/expert-applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<Object>> apply(
            @RequestParam String expertType,
            @RequestParam List<String> specialties,
            @RequestParam String introduction) {
        Long id = service.apply(expertType, specialties, introduction);
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "전문가 인증 신청 성공",
                                Map.of(
                                        "applicationId",
                                        id,
                                        "status",
                                        "PENDING_REVIEW",
                                        "submittedAt",
                                        java.time.Instant.now())));
    }

    @PostMapping("/expert-contents")
    ResponseEntity<ApiResponse<Object>> content(@Valid @RequestBody ExpertContentRequest q) {
        Long id = service.content(q);
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "전문가 콘텐츠 등록 성공",
                                Map.of(
                                        "contentId",
                                        id,
                                        "contentType",
                                        q.contentType(),
                                        "activityTypes",
                                        q.items().stream()
                                                .map(ExpertContentRequest.Item::activityType)
                                                .distinct()
                                                .toList(),
                                        "status",
                                        "DRAFT",
                                        "aiCurriculumStatus",
                                        "PENDING")));
    }

    @GetMapping("/expert-contents")
    ApiResponse<Object> contents() {
        List<Map<String, Object>> contents = service.contents();
        return ApiResponse.success(
                200,
                "전문가 콘텐츠 목록 조회 성공",
                Map.of(
                        "content",
                        contents,
                        "page",
                        0,
                        "size",
                        20,
                        "totalElements",
                        contents.size(),
                        "totalPages",
                        1,
                        "hasNext",
                        false));
    }

    @GetMapping("/expert-contents/{id}")
    ApiResponse<Object> content(@PathVariable Long id) {
        return ApiResponse.success(200, "전문가 콘텐츠 상세 조회 성공", service.content(id));
    }

    @PostMapping("/expert-contents/{id}/enrollments")
    ResponseEntity<ApiResponse<Object>> enroll(
            @PathVariable Long id, @RequestBody Map<String, Object> q) {
        Long e =
                service.enroll(
                        id,
                        String.valueOf(q.get("accessType")),
                        Boolean.parseBoolean(String.valueOf(q.get("personalizationAgreed"))));
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "커리큘럼 이용 시작 성공",
                                Map.of(
                                        "enrollmentId",
                                        e,
                                        "contentId",
                                        id,
                                        "status",
                                        "ACTIVE",
                                        "startedAt",
                                        java.time.LocalDate.now())));
    }

    @GetMapping("/meal-products/recommendations")
    ApiResponse<Object> products(@RequestParam Long routineId) {
        return ApiResponse.success(
                200, "식품 추천 조회 성공", Map.of("routineId", routineId, "products", service.products()));
    }

    @PostMapping("/meal-carts")
    ResponseEntity<ApiResponse<Object>> cart(@RequestBody Map<String, Object> q) {
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.success(
                                201,
                                "제휴 상품 장바구니 구성 성공",
                                service.cart(
                                        String.valueOf(q.get("partner")),
                                        (List<Map<String, Object>>)
                                                q.getOrDefault("items", List.of()))));
    }
}
