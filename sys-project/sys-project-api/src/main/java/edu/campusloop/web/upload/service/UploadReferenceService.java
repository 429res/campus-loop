package edu.campusloop.web.upload.service;
import edu.campusloop.common.ApiException;
import edu.campusloop.web.upload.entity.Upload;
import edu.campusloop.web.upload.mapper.UploadMapper;
import org.springframework.stereotype.Service;
import java.nio.file.*;
@Service
public class UploadReferenceService {
    private final UploadMapper uploads;private final LocalUploadService files;
    public UploadReferenceService(UploadMapper uploads,LocalUploadService files) {this.uploads=uploads;this.files=files;}
    private Upload owned(long actor,String id,String visibility) {
        Upload row=uploads.selectById(id);
        if(row==null || row.getOwnerId()!=actor || !visibility.equals(row.getVisibility())) throw invalid();return row;
    }
    public void publicImage(long actor,String url) {
        if(!url.matches("/uploads/[a-f0-9-]{36}\\.png")) throw invalid();
        var row=owned(actor,url.substring(9,url.length()-4),"PUBLIC");if(!url.equals(row.getUrl())) throw invalid();
    }
    public void privateEvidence(long actor,String id) {
        var row=owned(actor,id,"PRIVATE_EVIDENCE");
        if(!row.getUrl().equals("/api/history-evidence/"+id) || !Files.isRegularFile(files.evidencePath(id),LinkOption.NOFOLLOW_LINKS)) throw invalid();
    }
    private static ApiException invalid() {return new ApiException(400,"请使用本人上传且符合用途的有效图片引用");}
}
