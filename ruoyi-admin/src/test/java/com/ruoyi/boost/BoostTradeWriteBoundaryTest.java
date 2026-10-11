package com.ruoyi.boost;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real admission, suspension, Spring proxies and MySQL. Only unpaid orders are created. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostTradeWriteBoundaryTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL",matches=".+")
class BoostTradeWriteBoundaryTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource source(){return new BoostAbuseControlTest.Config().source();}
        @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean ControlledDb db(DataSource d){return new ControlledDb(new JdbcTemplate(d));}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
        @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
        @Bean BoostWechatGateway gateway(){return new BoostWechatGateway();}
        @Bean BoostPayments payments(BoostDb d,BoostWechatGateway g,BoostTrade t){return new BoostPayments(d,g,t);}
        @Bean BoostAdmin admin(BoostDb d,BoostTrade t,BoostPayments p){return new BoostAdmin(d,t,p);}
    }
    static class ControlledDb extends BoostDb {
        volatile Consumer<String> afterSelect=s->{},afterUpdate=s->{};
        ControlledDb(JdbcTemplate j){super(j);}
        @Override public List<Map<String,Object>> list(String sql,Object...args){var rows=super.list(sql,args);afterSelect.accept(sql);return rows;}
        @Override public long count(String sql,Object...args){long n=super.count(sql,args);afterSelect.accept(sql);return n;}
        @Override public int run(String sql,Object...args){int n=super.run(sql,args);afterUpdate.accept(sql);return n;}
    }
    @Autowired ControlledDb db;@Autowired BoostAuth auth;@Autowired BoostMarket market;@Autowired BoostTrade trade;
    @Autowired BoostAdmin admin;@Autowired BoostPayments payments;
    long buyer,worker,service,request,extraBuyer,extraWorker,extraService;String buyerToken,workerToken;ExecutorService pool;List<CountDownLatch> gates=new ArrayList<>();
    Map<String,Object> body(String kind){return map("requestKey",UUID.randomUUID().toString(),"kind",kind,"title","隔离未付款订单边界测试","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀","targetRank","王者","durationHours",24,"priceCents",12000,"description","测试状态与锁序，不执行付款或资金操作","images",List.of());}
    @BeforeEach void setup(){
        pool=Executors.newFixedThreadPool(2);ReflectionTestUtils.setField(trade,"paymentsEnabled",false);ReflectionTestUtils.setField(trade,"simulated",false);
        var a=auth.register(map("username","qa_tw_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","TradeBoundary2026!","nickname","隔离买家"));buyer=num((Map<String,Object>)a.get("user"),"id");buyerToken=str(a,"token");
        var b=auth.register(map("username","qa_tw_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","TradeBoundary2026!","nickname","隔离打手"));worker=num((Map<String,Object>)b.get("user"),"id");workerToken=str(b,"token");
        db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",worker);service=market.publish(worker,body("SERVICE"),null);request=market.publish(buyer,body("REQUEST"),null);
        db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id IN (?,?)",service,request);
    }
    CountDownLatch gate(){var g=new CountDownLatch(1);gates.add(g);return g;}
    static void await(CountDownLatch g){try{assertTrue(g.await(15,TimeUnit.SECONDS),"Controlled scheduling gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    void suspensionRace(boolean accept)throws Exception{
        long actor=accept?worker:buyer;MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("X-Boost-Token",accept?workerToken:buyerToken);assertEquals(actor,auth.member(r));
        CountDownLatch snapshot=gate(),release=gate();
        db.afterSelect=sql->{if(Thread.currentThread().getName().equals("checkout-after-admission")&&(sql.startsWith("SELECT owner_id FROM boost_listing")||sql.contains("certification='APPROVED' AND status='ACTIVE'"))){snapshot.countDown();await(release);}};
        Future<?> pending=pool.submit(()->{Thread.currentThread().setName("checkout-after-admission");return trade.create(actor,map("listingId",accept?request:service,"requestKey",UUID.randomUUID().toString(),"note","未付款边界测试"),accept);});
        try{
            await(snapshot);admin.moderate(1,"members",actor,map("action","block","note","隔离测试暂停用户"));
            assertEquals("BLOCKED",str(db.one("SELECT status FROM boost_member WHERE id=?",actor),"status"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_session WHERE member_id=?",actor));release.countDown();
            var error=assertThrows(ExecutionException.class,()->pending.get(20,TimeUnit.SECONDS));assertInstanceOf(BoostException.class,error.getCause());assertEquals(401,((BoostException)error.getCause()).code);
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE buyer_id=? OR worker_id=?",buyer,worker));
        }finally{release.countDown();}
    }
    @Test void checkoutCannotUseAnActiveSnapshotAfterBuyerSuspensionCommits()throws Exception{suspensionRace(false);}
    @Test void acceptanceCannotUseAnActiveSnapshotAfterWorkerSuspensionCommits()throws Exception{suspensionRace(true);}
    @Test void normalUnpaidCheckoutAndProxyCancellationRemainAvailable(){
        var o=trade.create(buyer,map("listingId",service,"requestKey",UUID.randomUUID().toString(),"note","未付款正常流程"),false);long id=num(o,"id");assertEquals("WAIT_PAY",str(o,"status"));
        payments.closeUnpaid(buyer,id);assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE paid_at IS NOT NULL"));
    }
    @Test void closingServiceReservationAndReusingItsCouponCannotInvertMemberCouponLocks()throws Exception{
        long coupon=db.insert("INSERT INTO boost_coupon(member_id,title,discount_cents,minimum_cents,expires_at) VALUES (?,?,?,?,?)",buyer,"隔离占用与返还测试券",1000,10000,java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(86400)));
        long old=num(trade.create(buyer,map("listingId",service,"requestKey",UUID.randomUUID().toString(),"couponId",coupon,"note","未付款占用测试"),false),"id");
        CountDownLatch releasedCoupon=gate(),continueClosing=gate(),checkoutEntered=gate();
        db.afterUpdate=sql->{if(Thread.currentThread().getName().equals("closing-coupon-first")&&sql.startsWith("UPDATE boost_coupon SET used_order_id=NULL")){releasedCoupon.countDown();await(continueClosing);}};
        db.afterSelect=sql->{if(Thread.currentThread().getName().equals("reusing-coupon-second")&&sql.startsWith("SELECT owner_id FROM boost_listing"))checkoutEntered.countDown();};
        Future<?> closing=pool.submit(()->{Thread.currentThread().setName("closing-coupon-first");payments.closeUnpaid(buyer,old);return null;});await(releasedCoupon);
        Future<Map<String,Object>> checkout=pool.submit(()->{Thread.currentThread().setName("reusing-coupon-second");return trade.create(buyer,map("listingId",service,"requestKey",UUID.randomUUID().toString(),"couponId",coupon,"note","取消后再次占用"),false);});await(checkoutEntered);continueClosing.countDown();
        assertDoesNotThrow(()->closing.get(20,TimeUnit.SECONDS));var fresh=assertDoesNotThrow(()->checkout.get(20,TimeUnit.SECONDS));
        assertEquals("CANCELLED",str(trade.detail(old,buyer),"status"));assertEquals("WAIT_PAY",str(fresh,"status"));assertEquals(11000,num(fresh,"amountCents"));
        assertEquals(num(fresh,"id"),db.count("SELECT used_order_id FROM boost_coupon WHERE id=?",coupon));payments.closeUnpaid(buyer,num(fresh,"id"));
    }
    @Test void expiredUnpaidReservationCanBeClosedForASuspendedBuyer(){
        long id=num(trade.create(worker,map("listingId",request,"requestKey",UUID.randomUUID().toString()),true),"id");
        admin.moderate(1,"members",buyer,map("action","block","note","隔离系统关闭停用账号订单"));db.run("UPDATE boost_order SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),id);
        payments.reconcileUnpaid(id);assertEquals("CANCELLED",str(trade.detail(id,worker),"status"));assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",request),"status"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE paid_at IS NOT NULL"));
    }
    @Test void disjointCheckoutsCannotDeadlockOnAnEmptyRequestKeyIndexGap()throws Exception{
        var a=auth.register(map("username","qa_tw_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","TradeBoundary2026!","nickname","另一隔离买家"));extraBuyer=num((Map<String,Object>)a.get("user"),"id");
        var b=auth.register(map("username","qa_tw_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","TradeBoundary2026!","nickname","另一隔离打手"));extraWorker=num((Map<String,Object>)b.get("user"),"id");db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",extraWorker);
        extraService=market.publish(extraWorker,body("SERVICE"),null);db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",extraService);
        CyclicBarrier emptyLookups=new CyclicBarrier(2);db.afterSelect=sql->{if(Thread.currentThread().getName().startsWith("cross-checkout-")&&sql.startsWith("SELECT * FROM boost_order WHERE buyer_id=? AND request_key=?"))try{emptyLookups.await(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}};
        String firstKey=UUID.randomUUID().toString(),secondKey=UUID.randomUUID().toString();
        Future<?> first=pool.submit(()->{Thread.currentThread().setName("cross-checkout-a");return trade.create(buyer,map("listingId",service,"requestKey",firstKey,"note","独立首次结单A"),false);});
        Future<?> second=pool.submit(()->{Thread.currentThread().setName("cross-checkout-b");return trade.create(extraBuyer,map("listingId",extraService,"requestKey",secondKey,"note","独立首次结单B"),false);});
        assertAll(()->assertDoesNotThrow(()->first.get(20,TimeUnit.SECONDS)),()->assertDoesNotThrow(()->second.get(20,TimeUnit.SECONDS)),
            ()->assertEquals(1,db.count("SELECT COUNT(*) FROM boost_order WHERE buyer_id=? AND request_key=?",buyer,firstKey)),
            ()->assertEquals(1,db.count("SELECT COUNT(*) FROM boost_order WHERE buyer_id=? AND request_key=?",extraBuyer,secondKey)));
    }
    @AfterEach void cleanup()throws Exception{
        gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));db.afterSelect=s->{};db.afterUpdate=s->{};
        assertEquals(0,db.count("SELECT COALESCE(SUM(balance_cents),0) FROM boost_member WHERE id IN (?,?,?,?)",buyer,worker,extraBuyer,extraWorker));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger"));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_withdrawal"));
        db.run("DELETE FROM boost_provider_event WHERE order_id IN (SELECT id FROM boost_order WHERE buyer_id IN (?,?) OR worker_id IN (?,?))",buyer,extraBuyer,worker,extraWorker);
        db.run("UPDATE boost_coupon SET used_order_id=NULL WHERE member_id IN (?,?,?,?)",buyer,worker,extraBuyer,extraWorker);db.run("DELETE FROM boost_notification WHERE member_id IN (?,?,?,?)",buyer,worker,extraBuyer,extraWorker);
        db.run("DELETE FROM boost_order WHERE buyer_id IN (?,?) OR worker_id IN (?,?)",buyer,extraBuyer,worker,extraWorker);db.run("DELETE FROM boost_listing WHERE owner_id IN (?,?,?,?)",buyer,worker,extraBuyer,extraWorker);
        db.run("DELETE FROM boost_audit WHERE target_id IN (?,?,?,?,?,?,?)",buyer,worker,service,request,extraBuyer,extraWorker,extraService);
        for(long id:new long[]{buyer,worker,extraBuyer,extraWorker})if(id>0){db.run("DELETE FROM boost_coupon WHERE member_id=?",id);db.run("DELETE FROM boost_session WHERE member_id=?",id);db.run("DELETE FROM boost_member WHERE id=?",id);}
    }
}
