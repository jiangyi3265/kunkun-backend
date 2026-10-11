package com.ruoyi.boost;
import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Anonymous;
@Anonymous @RestController
public class BoostMedia {
    private final BoostMediaFiles files;
    public BoostMedia(BoostMediaFiles files){this.files=files;}
    @PostMapping("/boost/upload")
    public Object upload(@RequestParam("file") MultipartFile file,@RequestParam String purpose,HttpServletRequest request)throws IOException{return BoostController.ok(files.upload(file,purpose,request));}
    @PostMapping({"/boost/media/access","/boost-admin/media/access"})
    public Object authorize(@RequestBody Map<String,Object> body,HttpServletRequest request){return BoostController.ok(files.authorize(body,request));}
    @GetMapping("/boost-media/{name}")
    public ResponseEntity<Resource> media(@PathVariable String name,@RequestParam(defaultValue="0") long expires,@RequestParam(defaultValue="") String actor,@RequestParam(defaultValue="") String signature)throws IOException{return files.media(name,expires,actor,signature);}
}
