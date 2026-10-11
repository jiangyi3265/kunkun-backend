package com.ruoyi.boost;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

@Service
public class BoostAdmin {
    private final BoostDb db;private final BoostTrade trade;private final BoostPayments payments;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    public BoostAdmin(BoostDb d,BoostTrade t,BoostPayments p){db=d;trade=t;payments=p;}
    public Map<String,Object> overview(){return map("members",db.count("SELECT COUNT(*) FROM boost_member"),"orders",db.count("SELECT COUNT(*) FROM boost_order"),"activeOrders",db.count("SELECT COUNT(*) FROM boost_order WHERE status IN ('WAIT_START','IN_PROGRESS','REVIEW','WAIT_DELIVERY','WAIT_CONFIRM')"),"pendingListings",db.count("SELECT COUNT(*) FROM boost_listing WHERE status='PENDING'"),"pendingCertifications",player!=null?db.count("SELECT COUNT(*) FROM boost_player_profile WHERE shop_status='PENDING'"):db.count("SELECT COUNT(*) FROM boost_member WHERE certification='PENDING'"),"pendingAftersales",db.count("SELECT COUNT(*) FROM boost_aftersale WHERE status='PENDING'"),"pendingWithdrawals",db.count("SELECT COUNT(*) FROM boost_withdrawal WHERE status='PENDING'"),"gmvCents",db.count("SELECT COALESCE(SUM(amount_cents),0) FROM boost_order WHERE status='COMPLETED'"),"recentOrders",db.list(BoostTrade.SELECT+" ORDER BY o.id DESC LIMIT 8"),"paymentsEnabled",trade.paymentsEnabled(),"simulatedPayment",trade.simulated());}
    public Map<String,Object> list(String type,Map<String,String> q){
        String select,from,statusCol="t.status",search;
        switch(type){
            case "shops": select="t.member_id AS id,t.shop_name,t.shop_intro,t.shop_revision,t.shop_submitted_at,t.shop_status,t.shop_proof,t.shop_review_note,t.realname_status,m.nickname,ph.phone,ph.verified_at AS phone_verified_at";from="boost_player_profile t JOIN boost_member m ON m.id=t.member_id LEFT JOIN boost_member_phone ph ON ph.member_id=t.member_id";search="t.shop_name";statusCol="t.shop_status";break;
            case "listings": select="t.*,m.nickname";from="boost_listing t JOIN boost_member m ON m.id=t.owner_id";search="t.title";break;
            case "members": select="t.id,t.username,t.nickname,t.avatar,t.status,t.certification,t.skill,t.proof,t.review_note,t.balance_cents,t.created_at";from="boost_member t";search="t.nickname";if("certification".equals(q.get("filter")))statusCol="t.certification";break;
            case "orders":select="t.*,b.nickname AS buyer_name,w.nickname AS worker_name";from="boost_order t JOIN boost_member b ON b.id=t.buyer_id JOIN boost_member w ON w.id=t.worker_id";search="t.order_no";break;
            case "aftersales":select="t.*,m.nickname,o.order_no,o.title";from="boost_aftersale t JOIN boost_member m ON m.id=t.member_id JOIN boost_order o ON o.id=t.order_id";search="o.order_no";break;
            case "withdrawals":select="t.*,m.nickname";from="boost_withdrawal t JOIN boost_member m ON m.id=t.member_id";search="m.nickname";break;
            case "audit":select="t.*";from="boost_audit t";search="t.action";statusCol=null;break;
            default:throw new BoostException(404,"未知业务列表");
        }
        String where=" WHERE 1=1";List<Object>args=new ArrayList<>();
        if(q.get("keyword")!=null&&!q.get("keyword").trim().isEmpty()){String keyword=q.get("keyword").trim();if(Arrays.asList("members","shops").contains(type)&&BoostPlayerMarket.isUserNoInput(keyword)){long found=BoostPlayerMarket.memberIdFromUserNo(keyword);where+=" AND "+("shops".equals(type)?"t.member_id":"t.id")+"=?";args.add(found);}else{where+=" AND "+search+" LIKE ?";args.add("%"+keyword+"%");}}
        if(statusCol!=null&&q.get("status")!=null&&!q.get("status").isEmpty()){where+=" AND "+statusCol+"=?";args.add(q.get("status"));}
        long total=db.count("SELECT COUNT(*) FROM "+from+where,args.toArray());args.add((page(q)-1)*20);
        List<Map<String,Object>> items=db.list("SELECT "+select+" FROM "+from+where+" ORDER BY "+("shops".equals(type)?"t.member_id":"t.id")+" DESC LIMIT 20 OFFSET ?",args.toArray());for(Map<String,Object> item:items)if(Arrays.asList("members","shops").contains(type))item.put("userNo",BoostPlayerMarket.userNo(num(item,"id")));if("members".equals(type)&&player!=null)for(Map<String,Object> member:items)member.put("playerProfile",player.profile(num(member,"id")));return map("items",items,"total",total);
    }
    private void audit(long admin,String action,long target,String note){db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,?,?,?)",admin,action,target,note);}
    @Transactional public void moderate(long admin,String type,long id,Map<String,Object>b){
        String action=choice(b,"action","approve","reject","offline","block","unblock"),note=text(b,"note",2,500);
        if("shops".equals(type)){player.moderateShop(admin,id,b);return;}
        if("listings".equals(type)){
            // Owner is immutable in the member API. Lock member before listing, matching
            // owner edits and avoiding a cycle through the notification's member foreign key.
            long ownerId=num(db.one("SELECT owner_id FROM boost_listing WHERE id=?",id),"ownerId");
            Map<String,Object> owner=db.one("SELECT id,status,certification FROM boost_member WHERE id=? FOR UPDATE",ownerId);
            Map<String,Object> l=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",id);String status=str(l,"status");
            BoostException.require(num(l,"ownerId")==ownerId,"发布归属发生变动，请刷新后重试");
            BoostException.require(("offline".equals(action)&&"ACTIVE".equals(status))||(("approve".equals(action)||"reject".equals(action))&&"PENDING".equals(status)),"当前发布状态不能执行该操作");
            if("approve".equals(action)){
                BoostException.require("ACTIVE".equals(str(owner,"status")),"发布者已停用，不能上架");
                if(BoostPlayerMarket.item(l)){BoostException.require(player!=null,"道具交易配置不可用");player.requirePublisher(ownerId,str(l,"kind"));}else{if(player!=null&&player.enabled())throw new BoostException(400,"历史代练发布不能重新上架");if("SERVICE".equals(str(l,"kind")))BoostException.require("APPROVED".equals(str(owner,"certification")),"发布者尚未通过打手认证");}
            }
            db.run("UPDATE boost_listing SET status=?,review_note=? WHERE id=?","approve".equals(action)?"ACTIVE":"reject".equals(action)?"REJECTED":"OFFLINE",note,id);
            db.notify(num(l,"ownerId"),"发布审核结果",note,null);
        }else if("members".equals(type)){
            Map<String,Object> m=db.one("SELECT * FROM boost_member WHERE id=? FOR UPDATE",id);
            if("block".equals(action)||"unblock".equals(action)){
                db.run("UPDATE boost_member SET status=? WHERE id=?","block".equals(action)?"BLOCKED":"ACTIVE",id);
                if("block".equals(action)){db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("UPDATE boost_listing SET status='OFFLINE',review_note='账户已停用' WHERE owner_id=? AND status='ACTIVE'",id);}
            }else{
                BoostException.require("PENDING".equals(str(m,"certification"))&&Arrays.asList("approve","reject").contains(action),"此认证申请已处理");
                db.run("UPDATE boost_member SET certification=?,review_note=? WHERE id=?","approve".equals(action)?"APPROVED":"REJECTED",note,id);
                db.notify(id,"打手认证审核结果",note,null);
            }
        }else throw new BoostException(400,"不支持该审核类型");
        audit(admin,type+":"+action,id,note);
    }
    @Transactional public void aftersale(long admin,long id,Map<String,Object>b){
        trade.requirePayments();
        String action=choice(b,"action","approve","reject"),note=text(b,"note",2,500);
        // Consistent lock order: sorted participants, order, then aftersale.
        Map<String,Object> initial=db.one("SELECT * FROM boost_aftersale WHERE id=?",id);
        long order=num(initial,"orderId");Map<String,Object> o=trade.lockOrder(order);
        Map<String,Object> c=db.one("SELECT * FROM boost_aftersale WHERE id=? FOR UPDATE",id);
        BoostException.require("PENDING".equals(str(c,"status"))&&"AFTERSALE".equals(str(o,"status")),"售后已处理");
        if("approve".equals(action)){
            if("WECHAT".equals(str(o,"paymentMode"))){
                payments.readyForRefund();db.run("UPDATE boost_order SET status='REFUNDING' WHERE id=?",order);
                db.run("UPDATE boost_aftersale SET status='REFUNDING',reply=? WHERE id=?",note,id);
                db.notify(num(o,"buyerId"),"退款申请已通过","退款正在提交微信处理，请关注订单状态。",order);
                audit(admin,"aftersale:refund-requested",id,note);return;
            }
            BoostException.require("SIMULATED".equals(str(o,"paymentMode"))&&trade.simulated(),"真实订单需完成微信原路退款后才能确认；当前不可模拟退款");
            db.run("UPDATE boost_order SET status='REFUNDED' WHERE id=?",order);
            db.run("UPDATE boost_aftersale SET status='REFUNDED',reply=? WHERE id=?",note,id);
            db.run("UPDATE boost_coupon SET used_order_id=NULL WHERE used_order_id=?",order);
            long balance=db.count("SELECT balance_cents FROM boost_member WHERE id=?",num(o,"buyerId"));
            db.run("INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents,order_id) VALUES (?,?,'REFUND','测试订单原路退款',?,?,?)",num(o,"buyerId"),"refund:"+order,num(o,"amountCents"),balance,order);
        }else{
            db.run("UPDATE boost_order SET status=? WHERE id=?",str(c,"previousStatus"),order);
            db.run("UPDATE boost_aftersale SET status='REJECTED',reply=? WHERE id=?",note,id);
        }
        db.notify(num(o,"buyerId"),"售后处理结果",note,order);db.notify(num(o,"workerId"),"售后处理结果",note,order);audit(admin,"aftersale:"+action,id,note);
    }
    @Transactional public void withdrawal(long admin,long id,Map<String,Object>b){
        trade.requirePayments();
        String action=choice(b,"action","approve","reject"),note=text(b,"note",2,500);
        Map<String,Object> initial=db.one("SELECT * FROM boost_withdrawal WHERE id=?",id);long member=num(initial,"memberId");
        Map<String,Object> m=db.one("SELECT * FROM boost_member WHERE id=? FOR UPDATE",member);
        Map<String,Object> w=db.one("SELECT * FROM boost_withdrawal WHERE id=? FOR UPDATE",id);
        BoostException.require("PENDING".equals(str(w,"status")),"提现申请已处理");
        if("approve".equals(action)){
            if(trade.simulated())db.run("UPDATE boost_withdrawal SET status='SIMULATED_PAID',note=? WHERE id=?",note,id);
            else{payments.readyForTransfer(w);db.run("UPDATE boost_withdrawal SET status='PROCESSING',note=? WHERE id=?",note,id);}
        }else{
            long amount=num(w,"amountCents");db.run("UPDATE boost_member SET balance_cents=balance_cents+? WHERE id=?",amount,member);
            db.run("UPDATE boost_withdrawal SET status='REJECTED',note=? WHERE id=?",note,id);
            db.run("INSERT INTO boost_ledger(member_id,event_key,kind,title,amount_cents,balance_cents) VALUES (?,?,'WITHDRAWAL_RETURN','提现未通过，余额退回',?,?)",member,"withdraw-return:"+id,amount,num(m,"balanceCents")+amount);
        }
        db.notify(member,"提现处理结果",note,null);audit(admin,"withdrawal:"+action,id,note);
    }
    public Map<String,Object> order(long id){Map<String,Object> o=db.one(BoostTrade.SELECT+" WHERE o.id=?",id);o.put("updates",BoostPlayerMarket.item(o)?Collections.emptyList():db.list("SELECT * FROM boost_progress WHERE order_id=? ORDER BY id DESC",id));if(BoostPlayerMarket.item(o))o.put("deliveries",db.list("SELECT id,note,images,created_at FROM boost_item_delivery WHERE order_id=?",id));return o;}
}
