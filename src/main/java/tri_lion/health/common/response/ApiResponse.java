package tri_lion.health.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, int code, String message, T data, List<FieldError> errors) {
    public static <T> ApiResponse<T> success(int code, String message, T data) {
        return new ApiResponse<>(true, code, message, data, null);
    }
    public static ApiResponse<Void> error(int code, String message, List<FieldError> errors) {
        return new ApiResponse<>(false, code, message, null, errors);
    }
    public record FieldError(String field, String reason) {}
}
