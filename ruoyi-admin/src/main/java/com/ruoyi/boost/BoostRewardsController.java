package com.ruoyi.boost;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import static com.ruoyi.boost.BoostController.ok;
@Anonymous @RestController @RequestMapping("/boost") public class BoostRewardsController {
 private final BoostRewards rewards;private final BoostAuth auth;private final BoostRateLimits limits;
 public BoostRewardsController(BoostRewards r,BoostAuth a,BoostRateLimits l){rewards=r;auth=a;limits=l;}
 @GetMapping("/rewards/goods") public Object goods(@RequestParam Map<String,String>q){return ok(rewards.goods(q,false));}
 @GetMapping("/rewards/goods/{id}") public Object good(@PathVariable long id){return ok(rewards.good(id,false));}
 @GetMapping("/rewards/activities") public Object activities(){return ok(rewards.activities(false));}
 @GetMapping("/rewards/activities/{id}") public Object activity(@PathVariable long id,HttpServletRequest r){return ok(rewards.activity(id,auth.optional(r),false));}
 @GetMapping("/rewards/badges/{id}") public Object badges(@PathVariable long id){return ok(rewards.badges(id));}
 @GetMapping("/rewards/points") public Object points(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(rewards.points(auth.member(r),q));}
 @GetMapping("/rewards/orders") public Object orders(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(rewards.orders(auth.member(r),q));}
 @GetMapping("/rewards/orders/{id}") public Object order(@PathVariable long id,HttpServletRequest r){return ok(rewards.detail(auth.member(r),id));}
 @PostMapping("/rewards/goods/{id}/redeem") public Object redeem(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){long member=auth.member(r);limits.hit("rewards:"+member,60,60);return ok(rewards.redeem(member,id,b));}
 @PutMapping("/rewards/orders/{id}/address") public Object address(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(rewards.address(auth.member(r),id,b));}
 @PostMapping("/rewards/orders/{id}/receive") public Object receive(@PathVariable long id,HttpServletRequest r){return ok(rewards.receive(auth.member(r),id));}
 @PostMapping("/rewards/activities/{id}/draw") public Object draw(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){long member=auth.member(r);limits.hit("rewards:"+member,60,60);return ok(rewards.draw(member,id,b));}
 @GetMapping("/rewards/draws") public Object draws(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(rewards.draws(auth.member(r),q));}
}
