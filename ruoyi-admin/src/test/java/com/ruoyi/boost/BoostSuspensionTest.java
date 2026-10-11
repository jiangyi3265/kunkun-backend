package com.ruoyi.boost;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Account suspension must also hold across related listings, follows and unpaid orders. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostFlowTest.Config.class)
@Transactional
class BoostSuspensionTest {
    @Autowired BoostDb db;
    @Autowired BoostAuth auth;
    @Autowired BoostMarket market;
    @Autowired BoostTrade trade;
    @Autowired BoostAdmin admin;
    @Autowired BoostPayments payments;
    long buyer,worker;

    @BeforeEach void setup() {
        buyer=member("需求用户");worker=member("接单用户");
        db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",worker);
    }
    long member(String nickname) {
        Map<String,Object> session=auth.register(map("username","suspend_"+UUID.randomUUID().toString().substring(0,8),"password","Suspension2026!","nickname",nickname));
        return num((Map<String,Object>)session.get("user"),"id");
    }
    void block(long id) {admin.moderate(1,"members",id,map("action","block","note","暂停账号进行核查"));}
    void unblock(long id) {admin.moderate(1,"members",id,map("action","unblock","note","核查结束恢复账号"));}
    long request(boolean approve) {
        long id=market.publish(buyer,map("requestKey",UUID.randomUUID().toString(),"kind","REQUEST","title","停用状态接单回归需求","game","王者荣耀","zone","微信区","platform","安卓","targetRank","王者","durationHours",24,"priceCents",12000,"description","核查停用状态下的发布和订单","images",Collections.emptyList()),null);
        if(approve)admin.moderate(1,"listings",id,map("action","approve","note","需求审核通过"));
        return id;
    }
    Map<String,Object> accept(long id) {return trade.create(worker,map("listingId",id,"requestKey",UUID.randomUUID().toString()),true);}

    @Test void cancellingReservedRequestDoesNotRepublishSuspendedOwner() {
        long listing=request(true),order=num(accept(listing),"id");
        block(buyer);payments.closeUnpaid(worker,order);
        assertEquals("CANCELLED",str(trade.detail(order,worker),"status"));
        assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));
        unblock(buyer);
        assertThrows(BoostException.class,()->market.detail(listing,worker),"Unblocking must not silently republish a moderated request");
        market.listingAction(buyer,listing,"resubmit");
        admin.moderate(1,"listings",listing,map("action","approve","note","恢复后重新审核"));
        assertEquals("WAIT_PAY",str(accept(listing),"status"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"),"No payment or settlement is part of this test");
    }

    @Test void activeLegacyRequestCannotCreateOrderForSuspendedBuyer() {
        long listing=request(true);block(buyer);
        // Reproduce state left by the old cancellation path in an isolated test database.
        db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",listing);
        assertThrows(BoostException.class,()->accept(listing));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE listing_id=?",listing));
        assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"),"Rejected creation must not reserve the listing");
    }

    @Test void pendingRequestOfSuspendedOwnerCannotBeApproved() {
        long listing=request(false);block(buyer);
        long before=db.count("SELECT COUNT(*) FROM boost_audit");
        assertThrows(BoostException.class,()->admin.moderate(1,"listings",listing,map("action","approve","note","不能批准停用用户的需求")));
        assertEquals("PENDING",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));
        assertEquals(before,db.count("SELECT COUNT(*) FROM boost_audit"));
    }

    @Test void followsAndProfileHideSuspendedShopsAndRecoverAfterUnblock() {
        assertTrue(market.follow(buyer,worker,true));block(worker);
        assertThrows(BoostException.class,()->market.shop(worker,buyer));
        assertEquals(0,num(market.follows(buyer,Collections.emptyMap()),"total"));
        assertEquals(0,num(auth.me(buyer),"follows"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_follow WHERE member_id=? AND seller_id=?",buyer,worker),"Keep the relationship for restoration");
        unblock(worker);
        assertEquals(1,num(market.follows(buyer,Collections.emptyMap()),"total"));
        assertEquals(1,num(auth.me(buyer),"follows"));
        assertEquals(true,market.shop(worker,buyer).get("followed"));
    }

    @Test void suspendedShopRejectsNewFollowButExistingFollowCanBeRemoved() {
        market.follow(buyer,worker,true);block(worker);
        long other=member("新的关注用户");
        assertThrows(BoostException.class,()->market.follow(other,worker,true));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_follow WHERE member_id=?",other));
        assertFalse(market.follow(buyer,worker,false));
        assertFalse(market.follow(buyer,worker,false));
        unblock(worker);
        assertEquals(0,num(market.follows(buyer,Collections.emptyMap()),"total"));
    }
}
