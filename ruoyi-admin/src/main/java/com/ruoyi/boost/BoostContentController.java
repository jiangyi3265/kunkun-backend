package com.ruoyi.boost;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.annotation.Anonymous;
import static com.ruoyi.boost.BoostController.ok;

@RestController public class BoostContentController {
 private final BoostContent content;private final BoostRewardsMedia media;
 public BoostContentController(BoostContent c,BoostRewardsMedia m){content=c;media=m;}
 @Anonymous @GetMapping("/boost/content/site") public Object site(){return ok(content.site());}
 @Anonymous @GetMapping("/boost/content/activities") public Object list(@RequestParam Map<String,String> q){return ok(content.activities(q,false));}
 @Anonymous @GetMapping("/boost/content/activities/{id}") public Object item(@PathVariable long id){return ok(content.activity(id,false));}
 @GetMapping("/boost-admin/content/site") @PreAuthorize("@boostAccess.can('boost:content:read')") public Object adminSite(){return ok(content.site());}
 @PutMapping("/boost-admin/content/site") @PreAuthorize("@boostAccess.can('boost:content:manage')") public Object saveSite(@RequestBody Map<String,Object> b){return ok(content.saveSite(SecurityUtils.getUserId(),b));}
 @GetMapping("/boost-admin/content/activities") @PreAuthorize("@boostAccess.can('boost:content:read')") public Object adminList(@RequestParam Map<String,String> q){return ok(content.activities(q,true));}
 @GetMapping("/boost-admin/content/activities/{id}") @PreAuthorize("@boostAccess.can('boost:content:read')") public Object adminItem(@PathVariable long id){return ok(content.activity(id,true));}
 @PostMapping("/boost-admin/content/activities") @PreAuthorize("@boostAccess.can('boost:content:manage')") public Object add(@RequestBody Map<String,Object> b){return ok(content.saveActivity(SecurityUtils.getUserId(),null,b));}
 @PutMapping("/boost-admin/content/activities/{id}") @PreAuthorize("@boostAccess.can('boost:content:manage')") public Object edit(@PathVariable long id,@RequestBody Map<String,Object> b){return ok(content.saveActivity(SecurityUtils.getUserId(),id,b));}
 @PostMapping("/boost-admin/content/upload") @PreAuthorize("@boostAccess.can('boost:content:manage')") public Object upload(@RequestParam MultipartFile file)throws java.io.IOException{return ok(media.upload(SecurityUtils.getUserId(),file));}
}
