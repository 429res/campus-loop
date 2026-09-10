package edu.campusloop.web.upload.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.ResultVo;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.upload.service.LocalUploadService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;
@RestController
public class UploadController {
    private final LocalUploadService files;
    public UploadController(LocalUploadService files) {this.files=files;}
    @PostMapping("/api/uploads") public ResultVo<Map<String,String>> upload(@RequestAttribute(AuthInterceptor.USER) User user,@RequestParam("file") MultipartFile file) throws IOException {
        return ResultVo.success(Map.of("url",files.store(user.getId(),file,false).getUrl()));
    }
    @PostMapping("/api/uploads/evidence") public ResultVo<Map<String,String>> evidence(@RequestAttribute(AuthInterceptor.USER) User user,@RequestParam("file") MultipartFile file) throws IOException {
        return ResultVo.success(Map.of("uploadId",files.store(user.getId(),file,true).getId()));
    }
    public record RotateRequest(@jakarta.validation.constraints.NotBlank String url) {}
    @PostMapping("/api/uploads/rotate") public ResultVo<Map<String,String>> rotate(@RequestAttribute(AuthInterceptor.USER) User user,@jakarta.validation.Valid @RequestBody RotateRequest request) throws IOException {
        return ResultVo.success(Map.of("url",files.rotate(user.getId(),request.url()).getUrl()));
    }
}
