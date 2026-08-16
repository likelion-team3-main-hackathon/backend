package tri_lion.health.controller.analysis;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import tri_lion.health.common.response.ApiResponse;
import tri_lion.health.service.analysis.AnalysisLabService;

@RestController
@RequestMapping("/api/v1/analysis-labs")
public class AnalysisLabController {
    private final AnalysisLabService service;

    public AnalysisLabController(AnalysisLabService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    ApiResponse<Object> overview(
            @RequestParam(defaultValue = "DAILY") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate anchorDate) {
        return ApiResponse.success(200, "종합 분석실 조회 성공", service.overview(period, anchorDate));
    }

    @GetMapping("/nutrition")
    ApiResponse<Object> nutrition(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(200, "식단 검사실 조회 성공", service.nutrition(from, to));
    }

    @GetMapping("/exercise")
    ApiResponse<Object> exercise(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(200, "운동 검사실 조회 성공", service.exercise(from, to));
    }

    @GetMapping("/body-composition")
    ApiResponse<Object> bodyComposition(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(200, "체성분 검사실 조회 성공", service.bodyComposition(from, to));
    }
}
