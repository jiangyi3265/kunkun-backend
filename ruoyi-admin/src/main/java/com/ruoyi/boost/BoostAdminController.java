package com.ruoyi.boost;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.utils.SecurityUtils;

@RestController @RequestMapping("/boost-admin")

public class BoostAdminController {
    private final BoostAdmin service;
    @org.springframework.beans.factory.annotation.Autowired private BoostPayments payments;
    @org.springframework.beans.factory.annotation.Autowired private BoostAccess access;
    @org.springframework.beans.factory.annotation.Autowired private BoostPlayerMarket player;
    @org.springframework.beans.factory.annotation.Autowired private BoostPhone phones;
    @org.springframework.beans.factory.annotation.Autowired private BoostMediaFiles files;
    public BoostAdminController(BoostAdmin s){service=s;}
    @PreAuthorize("@boostAccess.can('boost:shops:review')") @PutMapping("/shops/{id}") public Object editShop(@PathVariable long id,@RequestBody Map<String,Object>b){return BoostController.ok(player.editShop(SecurityUtils.getUserId(),id,b));}
    @PreAuthorize("@boostAccess.can('boost:shops:review')") @GetMapping("/shops/{id}/history") public Object shopHistory(@PathVariable long id,@RequestParam Map<String,String>q){return BoostController.ok(player.shopHistory(id,q));}
    @PreAuthorize("@boostAccess.can('boost:shops:review')") @PostMapping("/shops/{id}/phone/code") public Object phoneCode(@PathVariable long id,@RequestBody Map<String,Object>b,jakarta.servlet.http.HttpServletRequest r){return BoostController.ok(phones.send(id,b,"ADMIN_"+SecurityUtils.getUserId(),r.getRemoteAddr()));}
    @PreAuthorize("@boostAccess.can('boost:shops:review')") @PostMapping("/shops/{id}/phone/verify") public Object phoneVerify(@PathVariable long id,@RequestBody Map<String,Object>b){return BoostController.ok(phones.verify(id,b,"ADMIN_"+SecurityUtils.getUserId()));}
    @PreAuthorize("@boostAccess.can('boost:shops:review')") @PostMapping("/shops/{id}/proof") public Object proof(@PathVariable long id,@RequestParam("file") org.springframework.web.multipart.MultipartFile file)throws java.io.IOException{return BoostController.ok(files.uploadShopProof(id,SecurityUtils.getUserId(),file));}
    @PreAuthorize("@boostAccess.can('boost:overview:read')") @GetMapping("/overview") public Object overview(){Map<String,Object> data=service.overview();if(!access.can("boost:orders:read"))data.remove("recentOrders");return BoostController.ok(data);}
    @PreAuthorize("@boostAccess.list(#type)") @GetMapping("/list/{type}") public Object list(@PathVariable String type,@RequestParam Map<String,String>q){return BoostController.ok(service.list(type,q));}
    @PreAuthorize("@boostAccess.can('boost:orders:read')") @GetMapping("/orders/{id}") public Object order(@PathVariable long id){return BoostController.ok(service.order(id));}
    @PreAuthorize("@boostAccess.moderate(#type,#b)") @PostMapping("/moderate/{type}/{id}") public Object moderate(@PathVariable String type,@PathVariable long id,@RequestBody Map<String,Object>b){service.moderate(SecurityUtils.getUserId(),type,id,b);return BoostController.ok(true);}
    @PreAuthorize("@boostAccess.can('boost:aftersales:review')") @PostMapping("/aftersales/{id}") public Object aftersale(@PathVariable long id,@RequestBody Map<String,Object>b){service.aftersale(SecurityUtils.getUserId(),id,b);return BoostController.ok(true);}
    @PreAuthorize("@boostAccess.can('boost:withdrawals:review')") @PostMapping("/withdrawals/{id}") public Object withdrawal(@PathVariable long id,@RequestBody Map<String,Object>b){service.withdrawal(SecurityUtils.getUserId(),id,b);return BoostController.ok(true);}
    @PreAuthorize("@boostAccess.can('boost:finance:reconcile')")
    @PostMapping("/orders/{id}/reconcile")
    public Object reconcile(@PathVariable long id,@RequestBody Map<String,Object> b){
        String note=BoostInput.text(b,"note",2,500),action=BoostInput.choice(b,"action","refresh","retryRefund");
        if("retryRefund".equals(action))payments.retryRefund(SecurityUtils.getUserId(),id,note);
        else payments.refreshOrder(SecurityUtils.getUserId(),id,note);
        return BoostController.ok(true);
    }
}
