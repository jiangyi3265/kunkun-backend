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

/** InnoDB notification FK and member/listing lock order; actual proxies, no provider calls. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostListingModerationLockTest.Config.class)
@EnabledIfEnvironmentVariable(named="BOOST_TEST_DB_URL", matches=".+")
class BoostListingModerationLockTest {
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
    }
    @Autowired PausingDb db;
    @Autowired BoostAuth auth;
    @Autowired BoostMarket market;
    @Autowired BoostTrade trade;
    @Autowired BoostAdmin admin;
    long owner,listing;
    String token;
    ExecutorService pool;
    final List<CountDownLatch> gates=new ArrayList<>();
    @BeforeEach void setup(){
        pool=Executors.newFixedThreadPool(2);ReflectionTestUtils.setField(trade,"paymentsEnabled",false);ReflectionTestUtils.setField(trade,"simulated",false);
        Map<String,Object> registered=auth.register(map("username","qa_lm_"+UUID.randomUUID().toString().replace("-", "").substring(0,18),"password","ListingModeration2026!","nickname","隔离审核锁序用户"));
        owner=num((Map<String,Object>)registered.get("user"),"id");token=str(registered,"token");
        db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",owner);
        listing=market.publish(owner,body("首次提交待审核发布"),null);
    }
    Map<String,Object> body(String title){return map("requestKey",UUID.randomUUID().toString(),"kind","SERVICE","title",title,"game","王者荣耀","zone","微信区","platform","安卓","targetRank","王者","durationHours",24,"priceCents",12000,"description","只验证后台审核与拥有者写入的数据库锁序","images",Collections.emptyList());}
    CountDownLatch gate(){CountDownLatch g=new CountDownLatch(1);gates.add(g);return g;}
    static void await(CountDownLatch latch){try{assertTrue(latch.await(12,TimeUnit.SECONDS),"Race gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    Future<?> start(String name,Runnable job){return pool.submit(()->{Thread.currentThread().setName(name);job.run();});}
    void moderate(String action){admin.moderate(1,"listings",listing,map("action",action,"note","隔离审核结果"));}
    @AfterEach void cleanup()throws Exception{
        gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));db.before=s->{};db.after=s->{};
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_order WHERE buyer_id=? OR worker_id=?",owner,owner));
        assertEquals(0,db.count("SELECT balance_cents FROM boost_member WHERE id=?",owner));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_ledger WHERE member_id=?",owner));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_withdrawal WHERE member_id=?",owner));
        db.run("DELETE FROM boost_audit WHERE (action LIKE 'members:%' AND target_id=?) OR (action LIKE 'listings:%' AND target_id=?)",owner,listing);
        db.run("DELETE FROM boost_listing WHERE owner_id=?",owner);db.run("DELETE FROM boost_notification WHERE member_id=?",owner);db.run("DELETE FROM boost_coupon WHERE member_id=?",owner);db.run("DELETE FROM boost_session WHERE member_id=?",owner);db.run("DELETE FROM boost_member WHERE id=?",owner);
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_member WHERE id=?",owner));
    }
    @Test void adminApprovalAndAuthenticatedOwnerEditUseTheSameMemberListingLockOrder()throws Exception{
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Boost-Token",token);long authenticated=auth.member(request);assertEquals(owner,authenticated);
        CountDownLatch adminHasListing=gate(),releaseAdmin=gate(),ownerStarted=gate();
        db.after=sql->{if(Thread.currentThread().getName().equals("moderation-admin")&&sql.startsWith("SELECT * FROM boost_listing WHERE id=? FOR UPDATE")){adminHasListing.countDown();await(releaseAdmin);}};
        db.before=sql->{if(Thread.currentThread().getName().equals("moderation-owner")&&sql.startsWith("SELECT id,status FROM boost_member WHERE id=? FOR UPDATE"))ownerStarted.countDown();};
        Future<?> approval=start("moderation-admin",()->moderate("approve"));await(adminHasListing);
        Future<?> edit=start("moderation-owner",()->market.publish(authenticated,body("并发编辑后等待重新审核"),listing));await(ownerStarted);
        assertThrows(TimeoutException.class,()->edit.get(500,TimeUnit.MILLISECONDS));releaseAdmin.countDown();
        assertDoesNotThrow(()->approval.get(15,TimeUnit.SECONDS),"Approval notification FK must not deadlock against owner member lock");
        assertDoesNotThrow(()->edit.get(15,TimeUnit.SECONDS),"Owner edit must not deadlock against administrator listing lock");
        Map<String,Object> saved=db.one("SELECT status,title FROM boost_listing WHERE id=?",listing);assertEquals("PENDING",str(saved,"status"));assertEquals("并发编辑后等待重新审核",str(saved,"title"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_audit WHERE action='listings:approve' AND target_id=?",listing));
    }
    @Test void normalApprovalAndOwnerOfflineStillPersist(){moderate("approve");assertEquals("ACTIVE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));market.listingAction(owner,listing,"offline");assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));}
    @Test void administratorCanRejectPendingListingOwnedByBlockedMember(){admin.moderate(1,"members",owner,map("action","block","note","隔离停用账号"));moderate("reject");assertEquals("REJECTED",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));}
    @Test void blockedOwnerApprovalRemainsDeniedAndLegacyActiveListingCanBeOfflined(){
        admin.moderate(1,"members",owner,map("action","block","note","隔离停用账号"));assertThrows(BoostException.class,()->moderate("approve"));assertEquals("PENDING",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));
        // Explicit isolated legacy-state compatibility fixture, not restoring a real blocked listing.
        db.run("UPDATE boost_listing SET status='ACTIVE' WHERE id=?",listing);moderate("offline");assertEquals("OFFLINE",str(db.one("SELECT status FROM boost_listing WHERE id=?",listing),"status"));
    }
}
