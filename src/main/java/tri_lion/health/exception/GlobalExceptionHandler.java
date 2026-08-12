package tri_lion.health.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tri_lion.health.common.response.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> api(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(ApiResponse.error(e.status().value(), e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        var errors =
                e.getBindingResult().getFieldErrors().stream()
                        .map(x -> new ApiResponse.FieldError(x.getField(), x.getDefaultMessage()))
                        .toList();
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "요청 값이 올바르지 않습니다.", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> constraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage(), null));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "요청 값이 올바르지 않습니다.", null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> large() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(413, "파일은 10MB 이하만 업로드할 수 있습니다.", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unknown(Exception e) {
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(500, "서버 내부 오류가 발생했습니다.", null));
    }
}
