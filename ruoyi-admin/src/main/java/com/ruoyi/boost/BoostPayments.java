package com.ruoyi.boost;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static com.ruoyi.boost.BoostDb.*;

@Service
public class BoostPayments {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BoostPayments.class);
    private final BoostDb db;private final BoostWechatGateway wx;private final BoostTrade trade;
    public BoostPayments(BoostDb d,BoostWechatGateway w,BoostTrade t){db=d;wx=w;trade=t;}
    public Object prepay(long member,long id){
        trade.requirePayments();
        BoostException.require(!trade.simulated(),"测试环境请使用测试支付");
        wx.ensureConfigured();
        self().recordPaymentIntent(member,id);
        return self().requestPrepay(member,id);
    }
    public void requireEnabled(){trade.requirePayments();}
    private Map<String,Object> payableOrder(long member,long id){
        Map<String,Object> o=trade.lockOrder(id);
        BoostException.require(num(o,"buyerId")==member&&"WAIT_PAY".equals(str(o,"status")),"订单不可支付");
        trade.ensurePayableParticipants(o);
        String openid=str(db.one("SELECT openid FROM boost_member WHERE id=?",member),"openid");BoostException.require(!openid.isEmpty(),"请先绑定微信登录身份");
        o.put("payerOpenid",openid);return o;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordPaymentIntent(long member,long id){
        trade.requirePayments();
        payableOrder(member,id);
        // Commit before contacting the provider so even a process crash leaves a reconciliation marker.
        db.run("UPDATE boost_order SET payment_mode='WECHAT' WHERE id=?",id);
    }
    @Transactional
    public Object requestPrepay(long member,long id){
        trade.requirePayments();
        // Recheck after committing the intent: cancellation may have completed between transactions.
        // Keep the order lock until the remote creation finishes, serializing it with closeUnpaid.
        Map<String,Object> o=payableOrder(member,id);
        BoostException.require(db.count("SELECT COUNT(*) FROM boost_order WHERE id=? AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",id)==1,"订单付款时限已过，请刷新订单");
        Map<String,Object> body=map("appid",wx.appId,"mchid",wx.merchantId,"description",str(o,"title"),"out_trade_no",str(o,"orderNo"),"notify_url",wx.notifyBase+"/boost/wechat/payment-notify","amount",map("total",num(o,"amountCents"),"currency","CNY"),"payer",map("openid",str(o,"payerOpenid")));
        if(o.get("expiresAt")!=null)body.put("time_expire",java.sql.Timestamp.valueOf(str(o,"expiresAt").replace('T',' ')).toInstant().toString());
        Map<String,Object> r=wx.call("/v3/pay/transactions/jsapi",HttpMethod.POST,body);
        String prepay=str(r,"prepay_id");BoostException.require(!prepay.isEmpty(),"微信未返回支付单号");
        String time=String.valueOf(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-",""),pack="prepay_id="+prepay;
        return map("timeStamp",time,"nonceStr",nonce,"package",pack,"signType","RSA","paySign",wx.sign(wx.appId+"\n"+time+"\n"+nonce+"\n"+pack+"\n"));
    }
    public void paymentNotify(Map<String,Object> r){
        trade.requirePayments();
        BoostException.require(wx.appId.equals(str(r,"appid"))&&wx.merchantId.equals(str(r,"mchid")),"商户身份不匹配");
        if(!"TRANSACTION.SUCCESS".equals(str(r,"event_type"))||!"SUCCESS".equals(str(r,"trade_state")))return;
        Map<String,Object> amount=(Map<String,Object>)r.get("amount");BoostException.require("CNY".equals(str(amount,"currency")),"币种不匹配");
        trade.paymentConfirmed(str(r,"out_trade_no"),num(amount,"total"),str(r,"transaction_id"));
    }
    public void requestRefund(Map<String,Object> o,String reason){
        trade.requirePayments();
        wx.call("/v3/refund/domestic/refunds",HttpMethod.POST,map("out_trade_no",str(o,"orderNo"),"out_refund_no",refundNo(o),"reason",reason,"notify_url",wx.notifyBase+"/boost/wechat/refund-notify","amount",map("refund",num(o,"amountCents"),"total",num(o,"amountCents"),"currency","CNY")));
    }
    @Transactional public void refundNotify(Map<String,Object> r){
        trade.requirePayments();
        BoostException.require(wx.merchantId.equals(str(r,"mchid")),"退款商户不匹配");
        String no=str(r,"out_trade_no");Map<String,Object> o=trade.lockOrder(num(db.one("SELECT id FROM boost_order WHERE order_no=?",no),"id"));long id=num(o,"id");
        BoostException.require(refundNo(o).equals(str(r,"out_refund_no")),"退款单号不一致");
        Map<String,Object> amount=(Map<String,Object>)r.get("amount");BoostException.require(num(amount,"refund")==num(o,"amountCents")&&num(amount,"total")==num(o,"amountCents"),"退款金额不一致");
        if("REFUNDED".equals(str(o,"status")))return;
        BoostException.require(Arrays.asList("REFUNDING","REFUND_EXCEPTION").contains(str(o,"status")),"订单不在退款处理中");
        String state=str(r,"refund_status");
        if(Arrays.asList("ABNORMAL","CLOSED").contains(state)){
            if(!state.equals(str(o,"providerState"))){
                String note="ABNORMAL".equals(state)?"微信退款异常，请运营核对商户平台处理结果":"微信退款已关闭，需运营核对后重新发起";
                db.run("UPDATE boost_order SET status='REFUND_EXCEPTION',provider_state=?,provider_error=? WHERE id=?",state,note,id);
                db.run("UPDATE boost_aftersale SET status='REFUND_EXCEPTION' WHERE order_id=?",id);
                db.run("INSERT INTO boost_provider_event(order_id,event_type,provider_state,note) VALUES (?,'REFUND_EXCEPTION',?,?)",id,state,note);
                db.notify(num(o,"buyerId"),"退款需要平台跟进","支付渠道暂未完成退款，平台正在核对，请勿重复申请。",id);
                log.warn("Refund exception requires operator action: order {}, state {}",id,state);
            }return;
        }
        if("SUCCESS".equals(str(r,"refund_status"))){
            db.run("UPDATE boost_order SET status='REFUNDED',provider_state='SUCCESS',provider_error=NULL WHERE id=?",id);db.run("UPDATE boost_aftersale SET status='REFUNDED' WHERE order_id=?",id);db.run("UPDATE boost_coupon SET used_order_id=NULL WHERE used_order_id=?",id);
            long balance=db.count("SELECT balance_cents FROM boost_member WHERE id=?",num(o,"buyerId"));
            db.run("INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents,order_id) VALUES (?,?,'REFUND','微信原路退款',?,?,?)",num(o,"buyerId"),"refund:"+id,num(o,"amountCents"),balance,id);db.notify(num(o,"buyerId"),"退款已完成","退款已由微信原路退回，请查看微信账单。",id);
        }
    }
    public void readyForRefund(){trade.requirePayments();wx.ensureConfigured();}
    public void readyForTransfer(Map<String,Object>w){
        trade.requirePayments();
        wx.ensureConfigured();BoostException.require(!wx.transferScene.isEmpty(),"商家转账场景尚未配置");
        BoostException.require(db.count("SELECT COUNT(*) FROM boost_order WHERE worker_id=? AND payment_mode='SIMULATED'",num(w,"memberId"))==0,"测试账户不可提取真实资金，请使用独立生产数据库");
        BoostException.require(!str(db.one("SELECT openid FROM boost_member WHERE id=?",num(w,"memberId")),"openid").isEmpty(),"用户尚未绑定微信身份");
    }
    private String refundNo(Map<String,Object> order){return str(order,"refundNo").isEmpty()?"RF"+str(order,"orderNo"):str(order,"refundNo");}
    private boolean missing(BoostException e){return e instanceof BoostProviderException&&((BoostProviderException)e).missing();}
    private void queryIdentity(Map<String,Object> order,Map<String,Object> result){
        BoostException.require(wx.appId.equals(str(result,"appid"))&&wx.merchantId.equals(str(result,"mchid"))&&str(order,"orderNo").equals(str(result,"out_trade_no")),"渠道查单身份不匹配");
    }
    @Transactional public void closeUnpaid(long member,long id){
        Map<String,Object> o=trade.lockOrder(id);trade.participant(o,member);
        if("CANCELLED".equals(str(o,"status")))return;
        BoostException.require("WAIT_PAY".equals(str(o,"status")),"订单已付款或关闭");
        if("WECHAT".equals(str(o,"paymentMode"))){
            trade.requirePayments();
            Map<String,Object> r;
            try{r=wx.call("/v3/pay/transactions/out-trade-no/"+str(o,"orderNo")+"?mchid="+wx.merchantId,HttpMethod.GET,null);}
            catch(BoostException e){if(!missing(e))throw e;finishUnpaid(o,"ORDER_NOT_EXIST");return;}
            queryIdentity(o,r);String state=str(r,"trade_state");
            if("SUCCESS".equals(state)){r.put("event_type","TRANSACTION.SUCCESS");paymentNotify(r);return;}
            if(!Arrays.asList("CLOSED","REVOKED","PAYERROR").contains(state)){
                BoostException.require("NOTPAY".equals(state),"付款结果仍在确认，请稍后刷新");
                wx.call("/v3/pay/transactions/out-trade-no/"+str(o,"orderNo")+"/close",HttpMethod.POST,map("mchid",wx.merchantId));
            }
            finishUnpaid(o,"CLOSED");return;
        }
        finishUnpaid(o,"CANCELLED");
    }
    private void finishUnpaid(Map<String,Object> o,String providerState){
        long id=num(o,"id");db.run("UPDATE boost_order SET payment_mode=NULL,provider_state=?,provider_error=NULL WHERE id=?",providerState,id);
        trade.action(num(o,"buyerId"),id,"cancel");
        db.run("INSERT INTO boost_provider_event(order_id,event_type,provider_state,note) VALUES (?,'PAYMENT_CLOSED',?,'已确认未付款并释放占用')",id,providerState);
    }
    @Transactional public void reconcileUnpaid(long id){
        Map<String,Object> o=trade.lockOrder(id);if(!"WAIT_PAY".equals(str(o,"status")))return;
        if(db.count("SELECT COUNT(*) FROM boost_order WHERE id=? AND expires_at<=CURRENT_TIMESTAMP",id)>0){self().closeUnpaid(num(o,"buyerId"),id);return;}
        if(!"WECHAT".equals(str(o,"paymentMode")))return;
        trade.requirePayments();
        Map<String,Object> r;
        try{r=wx.call("/v3/pay/transactions/out-trade-no/"+str(o,"orderNo")+"?mchid="+wx.merchantId,HttpMethod.GET,null);}
        catch(BoostException e){if(!missing(e))throw e;finishUnpaid(o,"ORDER_NOT_EXIST");return;}
        queryIdentity(o,r);String state=str(r,"trade_state");
        if("SUCCESS".equals(state)){r.put("event_type","TRANSACTION.SUCCESS");paymentNotify(r);}
        else if(Arrays.asList("CLOSED","REVOKED","PAYERROR").contains(state))finishUnpaid(o,state);
    }
    @Transactional public void retryRefund(long admin,long id,String note){
        trade.requirePayments();
        Map<String,Object> o=trade.lockOrder(id);
        BoostException.require("REFUND_EXCEPTION".equals(str(o,"status"))&&"CLOSED".equals(str(o,"providerState")),"仅渠道已确认关闭的退款可重发；异常退款请先核对商户平台结果");
        wx.ensureConfigured();long attempt=num(o,"refundAttempt")+1;String number="RF"+str(o,"orderNo")+"A"+attempt;
        db.run("UPDATE boost_order SET status='REFUNDING',refund_attempt=?,refund_no=?,provider_state=NULL,provider_error=NULL WHERE id=?",attempt,number,id);
        db.run("UPDATE boost_aftersale SET status='REFUNDING',reply=? WHERE order_id=?",note,id);
        db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,'refund:retry',?,?)",admin,id,note);
    }
    public void refreshOrder(long admin,long id,String note){
        trade.requirePayments();
        Map<String,Object> order=db.one("SELECT * FROM boost_order WHERE id=?",id);
        if("WAIT_PAY".equals(str(order,"status")))self().reconcileUnpaid(id);
        else{
            BoostException.require(Arrays.asList("REFUNDING","REFUND_EXCEPTION").contains(str(order,"status")),"当前订单无需核对渠道");
            Map<String,Object> result=wx.call("/v3/refund/domestic/refunds/"+refundNo(order),HttpMethod.GET,null);
            result.put("mchid",wx.merchantId);result.put("refund_status",result.get("status"));self().refundNotify(result);
        }
        db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,'payment:reconcile',?,?)",admin,id,note);
    }
    public void reconcile(){
        // Unpaid reservations also expire in local simulation; no provider calls are made there.
        String unpaid=trade.paymentsEnabled()?"(payment_mode='WECHAT' OR expires_at<=CURRENT_TIMESTAMP)":"(payment_mode IS NULL AND expires_at<=CURRENT_TIMESTAMP)";
        for(Map<String,Object> o:db.list("SELECT * FROM boost_order WHERE status='WAIT_PAY' AND "+unpaid+" ORDER BY provider_checked_at,id LIMIT 30")){
            long id=num(o,"id");db.run("UPDATE boost_order SET provider_checked_at=CURRENT_TIMESTAMP WHERE id=?",id);
            try{self().reconcileUnpaid(id);}catch(BoostException e){db.run("UPDATE boost_order SET provider_error=? WHERE id=?",e.getMessage(),id);log.warn("Payment reconciliation pending for order {}: {}",id,e.getMessage());}
        }
        if(!trade.paymentsEnabled()||trade.simulated())return;
        for(Map<String,Object> o:db.list("SELECT o.*,a.reply FROM boost_order o JOIN boost_aftersale a ON a.order_id=o.id WHERE o.status IN ('REFUNDING','REFUND_EXCEPTION') ORDER BY o.provider_checked_at,o.id LIMIT 30")){
            db.run("UPDATE boost_order SET provider_checked_at=CURRENT_TIMESTAMP WHERE id=?",num(o,"id"));
            try{
                Map<String,Object>r;
                try{r=wx.call("/v3/refund/domestic/refunds/"+refundNo(o),HttpMethod.GET,null);}catch(BoostException ex){if(!missing(ex)||!"REFUNDING".equals(str(o,"status")))throw ex;requestRefund(o,str(o,"reply"));continue;}
                r.put("mchid",wx.merchantId);r.put("refund_status",r.get("status"));self().refundNotify(r);
            }catch(BoostException e){log.warn("Refund reconciliation pending for order {}: {}",num(o,"id"),e.getMessage());}
        }
        for(Map<String,Object>w:db.list("SELECT * FROM boost_withdrawal WHERE status IN ('PROCESSING','WAIT_USER_CONFIRM') ORDER BY provider_checked_at,id LIMIT 30")){
            db.run("UPDATE boost_withdrawal SET provider_checked_at=CURRENT_TIMESTAMP WHERE id=?",num(w,"id"));
            try{
                Map<String,Object>r;
                try{r=wx.call("/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/WD"+num(w,"id"),HttpMethod.GET,null);}catch(BoostException ex){if(!missing(ex))throw ex;r=transfer(w);}
                String state=str(r,"state");
                if("WAIT_USER_CONFIRM".equals(state)){
                    String pack=str(r,"package_info");
                    if(pack.isEmpty())pack=str(w,"confirmPackage");
                    if(pack.isEmpty())pack=str(transfer(w),"package_info");
                    db.run("UPDATE boost_withdrawal SET status='WAIT_USER_CONFIRM',provider_bill=?,confirm_package=?,note='请在微信小程序确认收款' WHERE id=? AND status IN ('PROCESSING','WAIT_USER_CONFIRM')",str(r,"transfer_bill_no"),pack,num(w,"id"));
                }
                else if(Arrays.asList("SUCCESS","FAIL","CANCELLED").contains(state)){
                    BoostException.require(("WD"+num(w,"id")).equals(str(r,"out_bill_no")),"查单结果与提现单号不一致");
                    r.put("mch_id",wx.merchantId);self().transferNotify(r);
                }
            }catch(BoostException e){log.warn("Transfer reconciliation pending for withdrawal {}: {}",num(w,"id"),e.getMessage());}
        }
    }
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationContext context;
    private BoostPayments self(){return context.getBean(BoostPayments.class);}
    public Map<String,Object> transfer(Map<String,Object> w){
        trade.requirePayments();
        BoostException.require(!wx.transferScene.isEmpty(),"商家转账场景尚未配置");long member=num(w,"memberId");String openid=str(db.one("SELECT openid FROM boost_member WHERE id=?",member),"openid");BoostException.require(!openid.isEmpty(),"用户尚未绑定微信身份");
        return wx.call("/v3/fund-app/mch-transfer/transfer-bills",HttpMethod.POST,map("appid",wx.appId,"out_bill_no","WD"+num(w,"id"),"transfer_scene_id",wx.transferScene,"openid",openid,"user_name",wx.encryptName(str(w,"accountName")),"transfer_amount",num(w,"amountCents"),"transfer_remark","游戏服务订单结算","notify_url",wx.notifyBase+"/boost/wechat/transfer-notify","transfer_scene_report_infos",wx.transferReports()));
    }
    @Transactional public void transferNotify(Map<String,Object> r){
        trade.requirePayments();
        BoostException.require(wx.merchantId.equals(str(r,"mch_id")),"转账商户不匹配");String no=str(r,"out_bill_no");BoostException.require(no.matches("WD[0-9]+"),"转账单号无效");long id=Long.parseLong(no.substring(2));
        Map<String,Object> initial=db.one("SELECT * FROM boost_withdrawal WHERE id=?",id);long member=num(initial,"memberId");Map<String,Object> m=db.one("SELECT * FROM boost_member WHERE id=? FOR UPDATE",member);Map<String,Object> w=db.one("SELECT * FROM boost_withdrawal WHERE id=? FOR UPDATE",id);
        BoostException.require(num(r,"transfer_amount")==num(w,"amountCents"),"转账金额不一致");
        BoostException.require(!str(m,"openid").isEmpty()&&str(m,"openid").equals(str(r,"openid")),"收款微信身份不一致");
        if(Arrays.asList("PAID","FAILED","REJECTED").contains(str(w,"status")))return;
        BoostException.require(Arrays.asList("PROCESSING","WAIT_USER_CONFIRM").contains(str(w,"status")),"转账状态不匹配");
        BoostException.require(num(r,"transfer_amount")==num(w,"amountCents"),"转账金额不一致");String state=str(r,"state");
        if("SUCCESS".equals(state)){db.run("UPDATE boost_withdrawal SET status='PAID',note='微信已确认转账成功' WHERE id=?",id);db.notify(member,"提现到账","微信已确认转账成功，请查看账单。",null);}
        else if(Arrays.asList("FAIL","CANCELLED").contains(state)){
            long amount=num(w,"amountCents");db.run("UPDATE boost_withdrawal SET status='FAILED',note='微信转账未完成，冻结余额已退回' WHERE id=?",id);db.run("UPDATE boost_member SET balance_cents=balance_cents+? WHERE id=?",amount,member);db.run("INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents) VALUES (?,?,'WITHDRAWAL_RETURN','微信转账未完成，余额退回',?,?)",member,"withdraw-return:"+id,amount,num(m,"balanceCents")+amount);
        }
    }
    public Object confirmation(long member,long id){
        trade.requirePayments();Map<String,Object>w=db.one("SELECT * FROM boost_withdrawal WHERE id=? AND member_id=?",id,member);BoostException.require("WAIT_USER_CONFIRM".equals(str(w,"status")),"当前无需确认收款");return map("mchId",wx.merchantId,"appId",wx.appId,"package",str(w,"confirmPackage"));}
}
