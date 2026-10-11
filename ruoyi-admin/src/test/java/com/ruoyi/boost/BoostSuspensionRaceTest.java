package com.ruoyi.boost;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** InnoDB locking subqueries and REPEATABLE READ snapshots require real MySQL. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostSuspensionRaceTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL", matches=".+")
class BoostSuspensionRaceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource source(){return new BoostAbuseControlTest.Config().source();}
        @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean PausingDb db(DataSource d){return new PausingDb(new JdbcTemplate(d));}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
        @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
        @Bean BoostWechatGateway gateway(){return new BoostWechatGateway();}
        @Bean BoostPayments payments(BoostDb d,BoostWechatGateway g,BoostTrade t){return new BoostPayments(d,g,t);}
        @Bean BoostAdmin admin(BoostDb d,BoostTrade t,BoostPayments p){return new BoostAdmin(d,t,p);}
    }
    static class PausingDb extends BoostDb {
        volatile Consumer<String> before=s->{},after=s->{};
        PausingDb(JdbcTemplate jdbc){super(jdbc);}
        @Override public List<Map<String,Object>> list(String sql,Object... args){before.accept(sql);List<Map<String,Object>> result=super.list(sql,args);after.accept(sql);return result;}
        @Override public int run(String sql,Object... args){before.accept(sql);int result=super.run(sql,args);after.accept(sql);return result;}
    }
    @Autowired PausingDb db;
    @Autowired BoostAuth auth;
    @Autowired BoostMarket market;
    @Autowired BoostTrade trade;
    @Autowired BoostPayments payments;
    @Autowired BoostAdmin admin;
    @Autowired PlatformTransactionManager tx;
    long buyer,worker,listing,order,coupon,replacementOrder;
    ExecutorService pool;
    final List<CountDownLatch> gates=new ArrayList<>();

    @BeforeEach void setup(TestInfo info){
        pool=Executors.newFixedThreadPool(2);
        buyer=member();worker=member();db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",worker);
        boolean couponFlow=info.getTestMethod().orElseThrow().getName().equals("serviceCancellationAndNewCouponOrderCannotDeadlock");
        listing=market.publish(couponFlow?worker:buyer,map("requestKey",UUID.randomUUID().toString(),"kind",couponFlow?"SERVICE":"REQUEST","title","停用与取消的并发需求","game","王者荣耀","zone","微信区","platform","安卓","targetRank","王者","durationHours",24,"priceCents",12000,"description","仅在隔离MySQL验证未付款需求锁序","images",Collections.emptyList()),null);
        admin.moderate(1,"listings",listing,map("action","approve","note","隔离测试审核通过"));
        Map<String,Object> body=map("listingId",listing,"requestKey",UUID.randomUUID().toString());
        if(couponFlow){coupon=db.insert("INSERT INTO boost_coupon(member_id,title,discount_cents,minimum_cents,expires_at) VALUES (?,?,?,?,?)",buyer,"并发取消回归券",1000,10000,Timestamp.from(Instant.now().plusSeconds(3600)));body.put("couponId",coupon);}
        order=num(trade.create(couponFlow?buyer:worker,body,!couponFlow),"id");
    }
    long member(){return num((Map<String,Object>)auth.register(map("username","qa_suspend_"+UUID.randomUUID().toString().replace("-","").substring(0,18),"password","SuspendRace2026!","nickname","停用并发测试")).get("user"),"id");}
    CountDownLatch gate(){CountDownLatch g=new CountDownLatch(1);gates.add(g);return g;}
    static void await(CountDownLatch latch){try{assertTrue(latch.await(12,TimeUnit.SECONDS),"Race gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    Future<?> start(String name,Runnable job){return pool.submit(()->{Thread.currentThread().setName(name);job.run();});}
    void block(){admin.moderate(1,"members",buyer,map("action","block","note","隔离测试停用账号"));}
    void assertClosed(){
        assertEquals("BLOCKED",str(db.one("SELECT status FROM boost_member WHERE id=?",buyer),"status"));
        assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));
        assertEquals("CANCELLED",str(trade.detail(order,worker),"status"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE member_id IN (?,?)",buyer,worker));
    }
    @AfterEach void cleanup()throws Exception{
        gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));
        db.before=s->{};db.after=s->{};
        for(long id:new long[]{order,replacementOrder})if(id>0){db.run("DELETE FROM boost_provider_event WHERE order_id=?",id);db.run("DELETE FROM boost_order WHERE id=?",id);}
        if(listing>0)db.run("DELETE FROM boost_listing WHERE id=?",listing);
        db.run("DELETE FROM boost_audit WHERE (action LIKE 'members:%' AND target_id IN (?,?)) OR (action LIKE 'listings:%' AND target_id=?)",buyer,worker,listing);
        for(long id:new long[]{buyer,worker})if(id>0){db.run("DELETE FROM boost_notification WHERE member_id=?",id);db.run("DELETE FROM boost_coupon WHERE member_id=?",id);db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("DELETE FROM boost_member WHERE id=?",id);}
    }

    @Test void cancellationCannotDeadlockAgainstSuspension()throws Exception{
        CountDownLatch suspended=gate(),finishSuspension=gate(),cancelling=gate();
        db.after=sql->{if(Thread.currentThread().getName().equals("suspend-first")&&sql.startsWith("UPDATE boost_member SET status=")){suspended.countDown();await(finishSuspension);}};
        db.before=sql->{if(Thread.currentThread().getName().equals("cancel-second")&&(sql.contains("FROM boost_member WHERE id=? FOR UPDATE")||sql.startsWith("UPDATE boost_listing SET status=CASE")))cancelling.countDown();};
        Future<?> blocking=start("suspend-first",this::block);await(suspended);
        Future<?> cancellation=start("cancel-second",()->payments.closeUnpaid(worker,order));await(cancelling);
        assertThrows(TimeoutException.class,()->cancellation.get(500,TimeUnit.MILLISECONDS),"Cancellation must wait for the uncommitted account status");
        finishSuspension.countDown();blocking.get(15,TimeUnit.SECONDS);cancellation.get(15,TimeUnit.SECONDS);assertClosed();
    }

    @Test void expiredCancellationDoesNotUseAnEarlierActiveOwnerSnapshot()throws Exception{
        CountDownLatch snapshotTaken=gate(),resume=gate();
        db.run("UPDATE boost_order SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),order);
        Future<?> cancellation=start("old-snapshot",()->{
            TransactionTemplate transaction=new TransactionTemplate(tx);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.execute(status->{assertEquals(1,db.count("SELECT COUNT(*) FROM boost_member WHERE id=? AND status='ACTIVE'",buyer));snapshotTaken.countDown();await(resume);payments.reconcileUnpaid(order);return null;});
        });
        await(snapshotTaken);block();resume.countDown();cancellation.get(15,TimeUnit.SECONDS);assertClosed();
    }

    @Test void serviceCancellationAndNewCouponOrderCannotDeadlock()throws Exception{
        CountDownLatch couponReleased=gate(),finishCancellation=gate(),newOrderReachedMemberLock=gate();
        db.after=sql->{
            if(Thread.currentThread().getName().equals("cancel-coupon")&&sql.startsWith("UPDATE boost_coupon SET used_order_id=NULL")){couponReleased.countDown();await(finishCancellation);}
        };
        // Checkout now waits on participants before the listing/coupon. Observe
        // that earlier lock attempt while preserving all final coupon assertions.
        db.before=sql->{if(Thread.currentThread().getName().equals("new-coupon-order")&&sql.startsWith("SELECT id FROM boost_member WHERE id=? FOR UPDATE"))newOrderReachedMemberLock.countDown();};
        Future<?> cancellation=start("cancel-coupon",()->payments.closeUnpaid(buyer,order));await(couponReleased);
        Future<?> creation=start("new-coupon-order",()->replacementOrder=num(trade.create(buyer,map("listingId",listing,"requestKey",UUID.randomUUID().toString(),"couponId",coupon),false),"id"));
        await(newOrderReachedMemberLock);assertThrows(TimeoutException.class,()->creation.get(500,TimeUnit.MILLISECONDS),"New checkout waits for the pending coupon release");
        finishCancellation.countDown();cancellation.get(15,TimeUnit.SECONDS);creation.get(15,TimeUnit.SECONDS);
        assertEquals("CANCELLED",str(trade.detail(order,buyer),"status"));
        assertEquals("WAIT_PAY",str(trade.detail(replacementOrder,buyer),"status"));
        assertNotEquals(order,replacementOrder);
        assertEquals(replacementOrder,db.count("SELECT used_order_id FROM boost_coupon WHERE id=?",coupon));
        assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE member_id IN (?,?)",buyer,worker));
        payments.closeUnpaid(buyer,order);
        assertEquals(replacementOrder,db.count("SELECT used_order_id FROM boost_coupon WHERE id=?",coupon),"Retrying the old cancellation cannot release the replacement's coupon");
        payments.closeUnpaid(buyer,replacementOrder);
        assertEquals("CANCELLED",str(trade.detail(replacementOrder,buyer),"status"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_coupon WHERE id=? AND used_order_id IS NULL",coupon));
    }
}
