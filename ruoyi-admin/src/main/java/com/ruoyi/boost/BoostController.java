package com.ruoyi.boost;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import static com.ruoyi.boost.BoostDb.*;

/** Member tokens are deliberately separate from RuoYi operator tokens.
 * Every non-public endpoint authenticates with BoostAuth before reading data. */
@Anonymous
@RestController
@RequestMapping("/boost")
public class BoostController {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostRewards rewards;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostIpProvince ipProvince;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostIdentity identity;
    @org.springframework.beans.factory.annotation.Autowired private BoostPhone phones;
    @org.springframework.beans.factory.annotation.Autowired private BoostRateLimits limits;
    @org.springframework.beans.factory.annotation.Autowired private BoostPolicy policy;
    @org.springframework.beans.factory.annotation.Value("${boost.registration-hourly-limit:5}") private int registrationLimit;
    private final BoostAuth auth;private final BoostMarket market;private final BoostTrade trade;private final BoostMessages chat;private final BoostDb db;private final BoostPayments payments;
    public BoostController(BoostAuth a,BoostMarket m,BoostTrade t,BoostMessages c,BoostDb d,BoostPayments p){auth=a;market=m;trade=t;chat=c;db=d;payments=p;}
    public static Map<String,Object> ok(Object data){return map("code",200,"data",data);}
    @GetMapping("/config") public Object config(){Map<String,Object> c=map("paymentsEnabled",trade.paymentsEnabled(),"simulatedPayment",trade.simulated(),"games",Arrays.asList("王者荣耀","和平精英","原神"),"policyVersion",policy.document().get("version"),"policyReady",policy.document().get("ready"),"brand","困困","support","交易有问题？在订单内申请售后，平台将协助处理。");if(player!=null&&player.enabled())c.putAll(player.config());c.put("contentEnabled",true);c.put("rewardsEnabled",rewards!=null);c.put("freePointsDrawEnabled",rewards!=null&&rewards.drawEnabled());return ok(c);}
    @GetMapping("/me/shop") public Object myShop(HttpServletRequest r){return ok(player.profile(auth.member(r)));}
    @PostMapping("/me/shop") public Object applyShop(@RequestBody Map<String,Object> b,HttpServletRequest r){long id=auth.member(r);limits.hit("shop:"+id,10,3600);player.applyShop(id,b);return ok(true);}
    @GetMapping("/me/growth") public Object growth(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(player.growth(auth.member(r),q));}
    @GetMapping("/me/growth/rules") public Object growthRules(HttpServletRequest r){auth.member(r);return ok(player.growthRules());}
    @PostMapping("/me/phone/code") public Object phoneCode(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(phones.send(auth.member(r),b,"MEMBER",r.getRemoteAddr()));}
    @PostMapping("/me/phone/verify") public Object phoneVerify(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(phones.verify(auth.member(r),b,"MEMBER"));}
    @GetMapping("/players/search") public Object players(@RequestParam String userNo,HttpServletRequest r){limits.hit("player-search:"+r.getRemoteAddr(),60,60);return ok(market.searchPlayer(userNo,auth.optional(r)));}
    @PostMapping("/me/identity/start") public Object startIdentity(@RequestBody Map<String,Object> b,HttpServletRequest r){long id=auth.member(r);limits.hit("identity-start:"+id,5,3600);return ok(identity.start(id,b));}
    @PostMapping("/me/identity/finish") public Object finishIdentity(@RequestBody Map<String,Object> b,HttpServletRequest r){long id=auth.member(r);limits.hit("identity-finish:"+id,30,3600);return ok(identity.finish(id,b));}
    @GetMapping("/policy") public Object policy(){return ok(policy.document());}
    @PostMapping("/auth/register") public Object register(@RequestBody Map<String,Object>b,HttpServletRequest r){limits.hit("register:"+r.getRemoteAddr(),registrationLimit,3600);return ok(auth.register(b));}
    @PostMapping("/auth/login") public Object login(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(auth.login(b,r.getRemoteAddr()));}
    @PostMapping("/auth/logout") public Object logout(HttpServletRequest r){auth.logout(r);return ok(true);}
    @PostMapping("/me/password") public Object password(@RequestBody Map<String,Object>b,HttpServletRequest r){auth.changePassword(auth.member(r),b);return ok(true);}
    @GetMapping("/me") public Object me(HttpServletRequest r){long id=auth.member(r);if(player!=null&&player.enabled()&&ipProvince!=null)player.observeProvince(id,ipProvince.province(r));return ok(auth.me(id));}
    @PutMapping("/me") public Object profile(@RequestBody Map<String,Object>b,HttpServletRequest r){auth.profile(auth.member(r),b);return ok(true);}
    @PostMapping("/me/certification") public Object apply(@RequestBody Map<String,Object>b,HttpServletRequest r){auth.apply(auth.member(r),b);return ok(true);}
    @GetMapping("/coupons") public Object coupons(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(BoostPages.query(db,q,"SELECT *","FROM boost_coupon WHERE member_id=?","expires_at DESC,id DESC",auth.member(r)));}
    @GetMapping("/listings") public Object listings(@RequestParam Map<String,String>q){return ok(market.listings(q,null));}
    @GetMapping("/listings/mine") public Object mine(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(market.listings(q,auth.member(r)));}
    @GetMapping("/listings/{id}") public Object detail(@PathVariable long id,HttpServletRequest r){return ok(market.detail(id,auth.optional(r)));}
    @GetMapping("/listings/{id}/reviews") public Object reviews(@PathVariable long id,@RequestParam Map<String,String>q,HttpServletRequest r){return ok(market.reviews(id,auth.optional(r),q));}
    @PostMapping("/listings") public Object publish(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(map("id",market.publish(auth.member(r),b,null)));}
    @PutMapping("/listings/{id}") public Object edit(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(map("id",market.publish(auth.member(r),b,id)));}
    @PostMapping("/listings/{id}/action") public Object listingAction(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){market.listingAction(auth.member(r),id,BoostInput.text(b,"action",1,20));return ok(true);}
    @PostMapping("/listings/{id}/favorite") public Object favorite(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){BoostException.require(b.get("desired") instanceof Boolean,"请提交收藏目标状态");return ok(market.favorite(auth.member(r),id,(Boolean)b.get("desired")));}
    @GetMapping("/saved/{type}") public Object saved(@PathVariable String type,@RequestParam Map<String,String>q,HttpServletRequest r){return ok(market.saved(auth.member(r),type,q));}
    @GetMapping("/shops/{id}") public Object shop(@PathVariable long id,HttpServletRequest r){return ok(market.shop(id,auth.optional(r)));}
    @PostMapping("/shops/{id}/follow") public Object follow(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){BoostException.require(b.get("desired") instanceof Boolean,"请提交关注目标状态");return ok(market.follow(auth.member(r),id,(Boolean)b.get("desired")));}
    @GetMapping("/follows") public Object follows(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(market.follows(auth.member(r),q));}
    @GetMapping("/orders") public Object orders(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(trade.orders(auth.member(r),q));}
    @PostMapping("/orders") public Object order(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(trade.create(auth.member(r),b,false));}
    @PostMapping("/orders/accept") public Object accept(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(trade.create(auth.member(r),b,true));}
    @GetMapping("/orders/{id}") public Object orderDetail(@PathVariable long id,HttpServletRequest r){return ok(trade.detail(id,auth.member(r)));}
    @PostMapping("/orders/{id}/simulated-pay") public Object pay(@PathVariable long id,HttpServletRequest r){trade.simulatedPay(auth.member(r),id);return ok(true);}
    @PostMapping("/orders/{id}/action") public Object action(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){long member=auth.member(r);String action=BoostInput.text(b,"action",1,30);if("cancel".equals(action))payments.closeUnpaid(member,id);else trade.action(member,id,action);return ok(true);}
    @PostMapping("/orders/{id}/progress") public Object progress(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){trade.progress(auth.member(r),id,b);return ok(true);}
    @PostMapping("/orders/{id}/delivery") public Object delivery(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){trade.delivery(auth.member(r),id,b);return ok(true);}
    @PostMapping("/orders/{id}/aftersale") public Object aftersale(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){trade.aftersale(auth.member(r),id,b);return ok(true);}
    @PostMapping("/orders/{id}/review") public Object review(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){trade.review(auth.member(r),id,b);return ok(true);}
    @GetMapping("/wallet") public Object wallet(HttpServletRequest r){return ok(trade.wallet(auth.member(r)));}
    @GetMapping("/wallet/ledger") public Object ledger(@RequestParam Map<String,String>q,HttpServletRequest r){String kind=q.getOrDefault("kind","");BoostException.require(Arrays.asList("","income","expense").contains(kind),"收支类型无效");String filter="income".equals(kind)?" AND amount_cents>0":"expense".equals(kind)?" AND amount_cents<0":"";return ok(BoostPages.query(db,q,"SELECT *","FROM boost_ledger WHERE member_id=?"+filter,"id DESC",auth.member(r)));}
    @GetMapping("/wallet/withdrawals") public Object withdrawals(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(BoostPages.query(db,q,"SELECT *","FROM boost_withdrawal WHERE member_id=?","id DESC",auth.member(r)));}
    @GetMapping("/messages/summary") public Object messageSummary(HttpServletRequest r){return ok(chat.summary(auth.member(r)));}
    @PostMapping("/wallet/withdraw") public Object withdraw(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(map("id",trade.withdraw(auth.member(r),b)));}
    @GetMapping("/conversations") public Object conversations(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(chat.conversations(auth.member(r),q));}
    @PostMapping("/conversations") public Object conversation(@RequestBody Map<String,Object>b,HttpServletRequest r){return ok(map("id",chat.open(auth.member(r),b)));}
    @GetMapping("/conversations/{id}") public Object messages(@PathVariable long id,@RequestParam(defaultValue="0")long before,@RequestParam(defaultValue="0")long after,HttpServletRequest r){return ok(chat.messages(auth.member(r),id,before,after));}
    @PostMapping("/conversations/{id}/messages") public Object send(@PathVariable long id,@RequestBody Map<String,Object>b,HttpServletRequest r){chat.send(auth.member(r),id,b);return ok(true);}
    @PostMapping("/conversations/{id}/read") public Object read(@PathVariable long id,@RequestParam(defaultValue="9223372036854775807")long through,HttpServletRequest r){chat.read(auth.member(r),id,through);return ok(true);}
    @PostMapping("/notifications/{id}/read") public Object readNotice(@PathVariable long id,HttpServletRequest r){chat.readNotice(auth.member(r),id);return ok(true);}
    @GetMapping("/notifications") public Object notifications(@RequestParam Map<String,String>q,HttpServletRequest r){return ok(chat.notifications(auth.member(r),q));}
    @PostMapping("/notifications/read-all") public Object readAll(HttpServletRequest r){chat.readAll(auth.member(r));return ok(true);}
}
