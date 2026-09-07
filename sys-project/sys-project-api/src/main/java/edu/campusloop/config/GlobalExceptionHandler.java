package edu.campusloop.config;
import edu.campusloop.common.*;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class) public ResponseEntity<ResultVo<Void>> api(ApiException e) { return error(e.getStatus(), e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<ResultVo<Void>> validation(MethodArgumentNotValidException e) {
        return error(400, e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getField() + ": " + x.getDefaultMessage()).orElse("参数不正确"));
    }
    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class}) public ResponseEntity<ResultVo<Void>> invalid(Exception e) { return error(400, e.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class}) public ResponseEntity<ResultVo<Void>> malformed(Exception e) { return error(400, "请求格式或参数不正确"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public ResponseEntity<ResultVo<Void>> tooLarge() { return error(413, "图片不得超过 5 MB"); }
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<ResultVo<Void>> conflict() { return error(409, "数据状态冲突，请刷新后重试"); }
    @ExceptionHandler(NoResourceFoundException.class) public ResponseEntity<ResultVo<Void>> missing() { return error(404, "资源不存在"); }
    @ExceptionHandler(Exception.class) public ResponseEntity<ResultVo<Void>> unknown(Exception e) {
        // Do not log request values, SQL bind parameters, credentials or tokens.
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Request failed: {}", e.getClass().getSimpleName());
        return error(500, "服务处理失败，请稍后重试");
    }
    private ResponseEntity<ResultVo<Void>> error(int status, String message) { return ResponseEntity.status(status).body(ResultVo.error(status, message)); }
}
