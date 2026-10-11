package com.ruoyi.boost;

import java.util.Map;
import static com.ruoyi.boost.BoostDb.*;

/** Caller holds the seller member lock; every change is in the order transaction. */
final class BoostItemStock {
    private BoostItemStock() {}
    static boolean unit(Map<String,Object> row){return "UNIT".equals(str(row,"pricingMode"));}
    static long available(Map<String,Object> row){return Math.max(0,num(row,"quantity")-num(row,"reservedQuantity")-num(row,"soldQuantity"));}
    static void reserve(BoostDb db,Map<String,Object> listing,long quantity){
        BoostException.require(quantity>0&&quantity<=available(listing),"可购买数量不足，请刷新库存");
        long remaining=available(listing)-quantity;
        db.run("UPDATE boost_listing SET reserved_quantity=reserved_quantity+?,status=? WHERE id=?",quantity,remaining==0?"RESERVED":"ACTIVE",num(listing,"id"));
    }
    static void paid(BoostDb db,Map<String,Object> order){
        long id=num(order,"listingId"),quantity=num(order,"quantity");
        Map<String,Object> listing=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",id);
        BoostException.require(num(listing,"reservedQuantity")>=quantity,"库存预订记录不一致，请联系平台");
        String status=str(listing,"status");
        if("RESERVED".equals(status)&&num(listing,"soldQuantity")+quantity>=num(listing,"quantity"))status="SOLD";
        db.run("UPDATE boost_listing SET reserved_quantity=reserved_quantity-?,sold_quantity=sold_quantity+?,status=? WHERE id=?",quantity,quantity,status,id);
    }
    static void release(BoostDb db,Map<String,Object> order,boolean eligible){
        long id=num(order,"listingId"),quantity=num(order,"quantity");
        Map<String,Object> listing=db.one("SELECT * FROM boost_listing WHERE id=? FOR UPDATE",id);
        BoostException.require(num(listing,"reservedQuantity")>=quantity,"库存预订记录不一致，请联系平台");
        String status=str(listing,"status");
        if("RESERVED".equals(status))status=eligible?"ACTIVE":"OFFLINE";
        if("ACTIVE".equals(status)&&!eligible)status="OFFLINE";
        db.run("UPDATE boost_listing SET reserved_quantity=reserved_quantity-?,status=? WHERE id=?",quantity,status,id);
    }
}
