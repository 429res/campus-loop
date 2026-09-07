package edu.campusloop.web.upload.controller;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.common.*;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.upload.entity.Upload;
import edu.campusloop.web.upload.mapper.UploadMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
@RestController
public class UploadController {
    private final Path directory; private final UploadMapper uploads;
    public UploadController(@Value("${campus.upload-dir}") String directory,UploadMapper uploads){this.directory=Path.of(directory).toAbsolutePath().normalize();this.uploads=uploads;}
    @PostMapping("/api/uploads") public ResultVo<Map<String,String>> upload(@RequestAttribute(AuthInterceptor.USER) User user,@RequestParam("file") MultipartFile file) throws IOException {
        if(file.isEmpty() || file.getSize()>5*1024*1024) throw new ApiException(400,"图片须为 1 字节至 5 MB");
        String type=file.getContentType();
        if(type==null || !Set.of("image/png","image/jpeg","image/gif").contains(type)) throw new ApiException(400,"支持 PNG、JPEG、GIF 图片");
        String id=UUID.randomUUID().toString();Path target=directory.resolve(id+".png").normalize();
        if(!target.getParent().equals(directory)) throw new ApiException(400,"无效上传路径");
        // Inspect dimensions before decoding; re-encode pixels to PNG (no SVG/script/EXIF payloads).
        try(InputStream input=file.getInputStream();ImageInputStream image=ImageIO.createImageInputStream(input)) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(image);
            if(!readers.hasNext()) throw new ApiException(400,"文件内容不是有效图片");
            ImageReader reader=readers.next();
            try {
                reader.setInput(image,true,true);
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","gif").contains(format)) throw new ApiException(400,"不支持的图片格式");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || (long)width*height>16_000_000) throw new ApiException(400,"图片尺寸最多 1600 万像素");
                Files.createDirectories(directory);
                try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {ImageIO.write(reader.read(0),"png",out);}
            } finally {reader.dispose();}
        } catch(javax.imageio.IIOException e) {Files.deleteIfExists(target);throw new ApiException(400,"图片内容损坏");}
        String url="/uploads/"+id+".png";
        try {
            Upload row=new Upload();row.setId(id);row.setOwnerId(user.getId());row.setUrl(url);row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));uploads.insert(row);
        } catch(RuntimeException e) {Files.deleteIfExists(target);throw e;}
        return ResultVo.success(Map.of("url",url));
    }
}
