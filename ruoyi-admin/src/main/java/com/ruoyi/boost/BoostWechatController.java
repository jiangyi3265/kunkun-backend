package com.ruoyi.boost;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;

@Anonymous @RestController @RequestMapping("/boost")
public class BoostWechatController {
    private final BoostAuth auth;private final BoostWechatGateway wx;private final BoostPayments payments;
    public BoostWechatController(BoostAuth a,BoostWechatGateway w,BoostPayments p){auth=a;wx=w;payments=p;}
    @PostMapping("/auth/wechat") public Object login(@RequestBody Map<String,Object>b){return BoostController.ok(auth.wechatWithPolicy(wx.openid(BoostInput.text(b,"code",5,256)),b));}
    @PostMapping("/auth/wechat-link") public Object link(@RequestBody Map<String,Object>b,HttpServletRequest r){long id=auth.member(r);return BoostController.ok(auth.wechat(wx.openid(BoostInput.text(b,"code",5,256)),id));}
    @PostMapping("/orders/{id}/wechat-pay") public Object prepay(@PathVariable long id,HttpServletRequest r){return BoostController.ok(payments.prepay(auth.member(r),id));}
    @GetMapping("/wallet/withdrawals/{id}/confirmation") public Object confirmation(@PathVariable long id,HttpServletRequest r){return BoostController.ok(payments.confirmation(auth.member(r),id));}
    @PostMapping("/wechat/payment-notify") public ResponseEntity<Void> payment(@RequestHeader HttpHeaders h,@RequestBody String raw){payments.requireEnabled();payments.paymentNotify(wx.callback(h,raw));return ResponseEntity.noContent().build();}
    @PostMapping("/wechat/refund-notify") public ResponseEntity<Void> refund(@RequestHeader HttpHeaders h,@RequestBody String raw){payments.requireEnabled();payments.refundNotify(wx.callback(h,raw));return ResponseEntity.noContent().build();}
    @PostMapping("/wechat/transfer-notify") public ResponseEntity<Void> transfer(@RequestHeader HttpHeaders h,@RequestBody String raw){payments.requireEnabled();payments.transferNotify(wx.callback(h,raw));return ResponseEntity.noContent().build();}
}
