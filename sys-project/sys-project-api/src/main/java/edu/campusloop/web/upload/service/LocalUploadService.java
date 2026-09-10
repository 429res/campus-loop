package edu.campusloop.web.upload.service;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.upload.entity.Upload;
import edu.campusloop.web.upload.mapper.UploadMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
@Service
public class LocalUploadService {
    private final Path directory,privateDirectory;private final UploadMapper uploads;
    public LocalUploadService(@Value("${campus.upload-dir}") String directory,UploadMapper uploads) {
        this.directory=Path.of(directory).toAbsolutePath().normalize();
        this.privateDirectory=this.directory.resolveSibling(this.directory.getFileName()+"-evidence");this.uploads=uploads;
    }
    public Upload store(long actor,MultipartFile file,boolean privateEvidence) throws IOException {
        if(file.isEmpty() || file.getSize()>10*1024*1024) throw new ApiException(400,"图片须为 1 字节至 10 MB");
        String type=file.getContentType();
        if(type==null || !Set.of("image/png","image/jpeg","image/gif").contains(type)) throw new ApiException(400,"支持 PNG、JPEG、GIF 图片");
        String id=UUID.randomUUID().toString();Path folder=privateEvidence?privateDirectory:directory;Path target=folder.resolve(id+".png").normalize();
        if(!target.getParent().equals(folder)) throw new ApiException(400,"无效上传路径");
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
                Files.createDirectories(folder);
                try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {ImageIO.write(reader.read(0),"png",out);}
            } finally {reader.dispose();}
        } catch(javax.imageio.IIOException e) {Files.deleteIfExists(target);throw new ApiException(400,"图片内容损坏");}
        String url=privateEvidence?"/api/history-evidence/"+id:"/uploads/"+id+".png";
        Upload row=new Upload();
        try {
            row.setId(id);row.setOwnerId(actor);row.setUrl(url);row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));row.setVisibility(privateEvidence?"PRIVATE_EVIDENCE":"PUBLIC");uploads.insert(row);
        } catch(RuntimeException e) {Files.deleteIfExists(target);throw e;}
        return row;
    }
    public Path evidencePath(String id) {
        if(id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new ApiException(404,"证据不存在或不可见");
        return privateDirectory.resolve(id+".png");
    }
}
