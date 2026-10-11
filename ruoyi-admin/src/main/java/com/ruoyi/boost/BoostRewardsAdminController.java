package com.ruoyi.boost;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.annotation.Anonymous;
import static com.ruoyi.boost.BoostController.ok;
@RestController public class BoostRewardsAdminController {
 private final BoostRewards rewards;private final BoostRewardsMedia media;
 public BoostRewardsAdminController(BoostRewards r,BoostRewardsMedia m){rewards=r;media=m;}
 @GetMapping("/boost-admin/rewards/goods") @PreAuthorize("@boostAccess.can('boost:rewards:read')") public Object goods(@RequestParam Map<String,String>q){return ok(rewards.goods(q,true));}
 @GetMapping("/boost-admin/rewards/activities") @PreAuthorize("@boostAccess.can('boost:rewards:read')") public Object activities(){return ok(rewards.activities(true));}
 @GetMapping("/boost-admin/rewards/activities/{id}") @PreAuthorize("@boostAccess.can('boost:rewards:read')") public Object activity(@PathVariable long id){return ok(rewards.activity(id,null,true));}
 @PostMapping("/boost-admin/rewards/goods") @PreAuthorize("@boostAccess.can('boost:rewards:manage')") public Object addGoods(@RequestBody Map<String,Object>b){return ok(rewards.saveGoods(SecurityUtils.getUserId(),null,b));}
 @PutMapping("/boost-admin/rewards/goods/{id}") @PreAuthorize("@boostAccess.can('boost:rewards:manage')") public Object editGoods(@PathVariable long id,@RequestBody Map<String,Object>b){return ok(rewards.saveGoods(SecurityUtils.getUserId(),id,b));}
 @PostMapping("/boost-admin/rewards/activities") @PreAuthorize("@boostAccess.can('boost:rewards:manage')") public Object addActivity(@RequestBody Map<String,Object>b){return ok(rewards.saveActivity(SecurityUtils.getUserId(),null,b));}
 @PutMapping("/boost-admin/rewards/activities/{id}") @PreAuthorize("@boostAccess.can('boost:rewards:manage')") public Object editActivity(@PathVariable long id,@RequestBody Map<String,Object>b){return ok(rewards.saveActivity(SecurityUtils.getUserId(),id,b));}
 @PostMapping("/boost-admin/rewards/points/{member}") @PreAuthorize("@boostAccess.can('boost:rewards:grant')") public Object grant(@PathVariable long member,@RequestBody Map<String,Object>b){return ok(rewards.grant(SecurityUtils.getUserId(),member,b));}
 @GetMapping("/boost-admin/rewards/points/{member}") @PreAuthorize("@boostAccess.can('boost:rewards:grant')") public Object points(@PathVariable long member,@RequestParam Map<String,String>q){return ok(rewards.points(member,q));}
 @GetMapping("/boost-admin/rewards/orders") @PreAuthorize("@boostAccess.can('boost:rewards:fulfill')") public Object orders(@RequestParam Map<String,String>q){return ok(rewards.adminOrders(q));}
 @PostMapping("/boost-admin/rewards/orders/{id}/ship") @PreAuthorize("@boostAccess.can('boost:rewards:fulfill')") public Object ship(@PathVariable long id,@RequestBody Map<String,Object>b){return ok(rewards.ship(SecurityUtils.getUserId(),id,b));}
 @PostMapping("/boost-admin/rewards/upload") @PreAuthorize("@boostAccess.can('boost:rewards:manage')") public Object upload(@RequestParam MultipartFile file)throws java.io.IOException{return ok(media.upload(SecurityUtils.getUserId(),file));}
 @Anonymous @GetMapping("/boost-reward-media/{name:.+}") public Object image(@PathVariable String name){return media.read(name);}
}
