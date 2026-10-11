package com.ruoyi.boost;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real service proxies and independent database connections; no test-wide transaction.
 * Barriers only pause database reads or the fake remote provider, never change results. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostRaceTest.Config.class)
class BoostRaceTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource d=new JdbcDataSource();
            d.setURL("jdbc:h2:mem:boost_races;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
            new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(d);BoostMigrations.migrate(new BoostDb(new JdbcTemplate(d)));return d;
        }
        @Bean JdbcTemplate jdbc(DataSource d){return new JdbcTemplate(d);}
        @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean PausingDb db(JdbcTemplate j){return new PausingDb(j);}
        @Bean BoostAuth auth(BoostDb d){return new BoostAuth(d);}
        @Bean BoostMarket market(BoostDb d,BoostAuth a){return new BoostMarket(d,a);}
        @Bean BoostTrade trade(BoostDb d,BoostAuth a){return new BoostTrade(d,a);}
        @Bean FakeGateway wx(){return new FakeGateway();}
        @Bean BoostPayments payments(BoostDb d,BoostWechatGateway w,BoostTrade t){return new BoostPayments(d,w,t);}
        @Bean BoostAdmin admin(BoostDb d,BoostTrade t,BoostPayments p){return new BoostAdmin(d,t,p);}
    }
    static class PausingDb extends BoostDb {
        volatile Consumer<String> beforeRead=s->{},afterRead=s->{};
        PausingDb(JdbcTemplate j){super(j);}
        @Override public List<Map<String,Object>> list(String sql,Object... args){
            beforeRead.accept(sql);List<Map<String,Object>> rows=super.list(sql,args);afterRead.accept(sql);return rows;
        }
    }
    static class Gate {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        void pause(){entered.countDown();await(release);}
        void reached(){await(entered);}
        void open(){release.countDown();}
    }
    static void await(CountDownLatch latch){
        try{assertTrue(latch.await(10,TimeUnit.SECONDS),"Barrier timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    static class FakeGateway extends BoostWechatGateway {
        volatile Gate createGate;volatile boolean timeout,remoteOpen;
        final List<String> events=new CopyOnWriteArrayList<>();
        @Override public void ensureConfigured(){}
        @Override public String sign(String s){return "test-signature";}
        @Override public Map<String,Object> call(String path,HttpMethod method,Map<String,Object> payload){
            if(path.equals("/v3/pay/transactions/jsapi")){
                if(createGate!=null)createGate.pause();events.add("create");remoteOpen=true;
                if(timeout)throw new BoostException(503,"模拟渠道已受理但响应超时");
                return map("prepay_id","test-prepay");
            }
            if(path.endsWith("/close")){events.add("close");remoteOpen=false;return map();}
            if(method==HttpMethod.GET){events.add("query");return map("trade_state","NOTPAY","appid",appId,"mchid",merchantId,"out_trade_no",path.substring(path.indexOf("out-trade-no/")+13).split("\\?")[0]);}
            throw new AssertionError("Unexpected provider request: "+path);
        }
    }
    @Autowired PausingDb db;@Autowired BoostAuth auth;@Autowired BoostMarket market;
    @Autowired BoostTrade trade;@Autowired BoostPayments payments;@Autowired BoostAdmin admin;@Autowired FakeGateway wx;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path media;
    ExecutorService executor;final List<Gate> gates=new ArrayList<>();
    long buyer,worker,listing;String username;
    static final String PASSWORD="RaceTest2026!",NEXT_PASSWORD="ChangedRace2026!";
    @BeforeEach void setup()throws Exception{
        executor=Executors.newFixedThreadPool(2);db.beforeRead=s->{};db.afterRead=s->{};
        wx.createGate=null;wx.timeout=false;wx.remoteOpen=false;wx.events.clear();
        wx.appId="test-app";wx.merchantId="test-merchant";wx.notifyBase="https://test.invalid";
        ReflectionTestUtils.setField(db,"mediaDirectory",media.toString());
        java.nio.file.Files.write(media.resolve("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"),new byte[]{1});
        ReflectionTestUtils.setField(trade,"simulated",false);
        username="race_"+UUID.randomUUID().toString().substring(0,8);buyer=register(username);
        worker=register("race_"+UUID.randomUUID().toString().substring(0,8));
        db.run("UPDATE boost_member SET certification='APPROVED' WHERE id=?",worker);
        db.run("UPDATE boost_member SET openid=? WHERE id=?","race_openid_"+buyer,buyer);
        db.run("DELETE FROM boost_media WHERE name=?","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png");
        db.run("INSERT INTO boost_media(name,owner_id,purpose,size_bytes) VALUES (?,?,?,?)","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",buyer,"CERTIFICATION",1);
        listing=market.publish(worker,map("requestKey",UUID.randomUUID().toString(),"kind","SERVICE","title","并发回归测试服务","game","王者荣耀","zone","微信区","platform","安卓","currentRank","星耀 III","targetRank","星耀 I","durationHours",24,"priceCents",12000,"description","隔离数据库中的并发验证服务","images",Collections.emptyList()),null);
        admin.moderate(1,"listings",listing,map("action","approve","note","测试通过"));
    }
    @AfterEach void cleanup()throws Exception{
        gates.forEach(Gate::open);executor.shutdown();
        if(!executor.awaitTermination(15,TimeUnit.SECONDS)){executor.shutdownNow();fail("Unfinished race worker");}
        db.beforeRead=s->{};db.afterRead=s->{};
    }
    long register(String name){return num((Map<String,Object>)auth.register(map("username",name,"password",PASSWORD,"nickname","并发测试用户")).get("user"),"id");}
    Gate gate(){Gate g=new Gate();gates.add(g);return g;}
    <T> Future<T> start(String name,Callable<T> work){return executor.submit(()->{Thread.currentThread().setName(name);return work.call();});}
    void allowContenderToFinishOrWait(Future<?> f)throws Exception{
        // On vulnerable code the competing operation finishes; with row locks it waits.
        try{f.get(700,TimeUnit.MILLISECONDS);}catch(TimeoutException expected){}
    }
    void assertRevoked(String token){MockHttpServletRequest r=new MockHttpServletRequest();r.addHeader("X-Boost-Token",token);assertEquals(401,assertThrows(BoostException.class,()->auth.member(r)).code);}

    @Test void passwordChangeRevokesAnAlreadyVerifyingLogin()throws Exception{
        Gate loginRead=gate();CountDownLatch changeStarted=new CountDownLatch(1);
        db.afterRead=s->{if(Thread.currentThread().getName().equals("old-login")&&s.contains("WHERE username=?"))loginRead.pause();};
        db.beforeRead=s->{if(Thread.currentThread().getName().equals("change-password"))changeStarted.countDown();};
        Future<Map<String,Object>> login=start("old-login",()->auth.login(map("username",username,"password",PASSWORD),"race-ip"));
        loginRead.reached();Future<?> change=start("change-password",()->{auth.changePassword(buyer,map("oldPassword",PASSWORD,"newPassword",NEXT_PASSWORD));return null;});
        await(changeStarted);allowContenderToFinishOrWait(change);loginRead.open();
        String token=str(login.get(10,TimeUnit.SECONDS),"token");change.get(10,TimeUnit.SECONDS);
        assertRevoked(token);
        assertThrows(BoostException.class,()->auth.login(map("username",username,"password",PASSWORD),"race-ip"));
        assertNotNull(auth.login(map("username",username,"password",NEXT_PASSWORD),"race-ip").get("token"));
    }
    @Test void loginWaitsForAnInFlightPasswordChange()throws Exception{
        Gate changing=gate();CountDownLatch loginStarted=new CountDownLatch(1);
        db.afterRead=s->{if(Thread.currentThread().getName().equals("change-first")&&s.contains("password_hash"))changing.pause();};
        db.beforeRead=s->{if(Thread.currentThread().getName().equals("login-second"))loginStarted.countDown();};
        Future<?> change=start("change-first",()->{auth.changePassword(buyer,map("oldPassword",PASSWORD,"newPassword",NEXT_PASSWORD));return null;});
        changing.reached();Future<Boolean> login=start("login-second",()->{try{auth.login(map("username",username,"password",PASSWORD),"race-ip");return true;}catch(BoostException e){assertEquals(400,e.code);return false;}});
        await(loginStarted);allowContenderToFinishOrWait(login);changing.open();change.get(10,TimeUnit.SECONDS);
        assertFalse(login.get(10,TimeUnit.SECONDS),"Old password must be checked after the pending password change");
    }
    @Test void duplicateCertificationCannotOverwriteAnApprovedApplication()throws Exception{
        Gate firstRead=gate();CountDownLatch secondStarted=new CountDownLatch(1);
        db.afterRead=s->{if(Thread.currentThread().getName().equals("first-application")&&s.contains("FROM boost_member WHERE id=?"))firstRead.pause();};
        db.beforeRead=s->{if(Thread.currentThread().getName().equals("second-application"))secondStarted.countDown();};
        Map<String,Object> proof=map("skill","王者荣耀第一份申请","images",Collections.singletonList("/boost-media/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"));
        Future<?> first=start("first-application",()->{auth.apply(buyer,proof);return null;});firstRead.reached();
        Future<Boolean> second=start("second-application",()->{
            boolean accepted=true;try{auth.apply(buyer,map("skill","王者荣耀第二份申请","images",proof.get("images")));}catch(BoostException e){accepted=false;}
            admin.moderate(1,"members",buyer,map("action","approve","note","认证审核通过"));return accepted;
        });
        await(secondStarted);allowContenderToFinishOrWait(second);firstRead.open();first.get(10,TimeUnit.SECONDS);
        boolean duplicateAccepted=second.get(10,TimeUnit.SECONDS);
        assertEquals("APPROVED",str(auth.me(buyer),"certification"),"Late duplicate must not reset an approved certification");
        assertFalse(duplicateAccepted,"Only one concurrent application may be accepted");
        assertEquals("王者荣耀第一份申请",str(auth.me(buyer),"skill"));
    }
    @Test void blockedWechatLoginCannotLeaveASessionForAfterUnblocking()throws Exception{
        Gate readIdentity=gate();
        db.afterRead=s->{if(Thread.currentThread().getName().equals("wechat-login")&&s.contains("WHERE openid=?"))readIdentity.pause();};
        Future<?> login=start("wechat-login",()->auth.wechat("race_openid_"+buyer,null));readIdentity.reached();
        admin.moderate(1,"members",buyer,map("action","block","note","测试停用账号"));readIdentity.open();
        ExecutionException failure=assertThrows(ExecutionException.class,()->login.get(10,TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof BoostException);
        admin.moderate(1,"members",buyer,map("action","unblock","note","测试恢复账号"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_session WHERE member_id=?",buyer));
    }
    long order(){return num(trade.create(buyer,map("listingId",listing,"requestKey",UUID.randomUUID().toString()),false),"id");}
    @Test void cancellationCannotOvertakePrepayAndIntentIsAlreadyDurable()throws Exception{
        long id=order();wx.createGate=gate();CountDownLatch cancelStarted=new CountDownLatch(1);
        db.beforeRead=s->{if(Thread.currentThread().getName().equals("cancel-order"))cancelStarted.countDown();};
        Future<?> prepay=start("prepay-order",()->payments.prepay(buyer,id));wx.createGate.reached();
        // Independent connection must see the committed intent while the provider is still blocked.
        assertEquals("WECHAT",str(db.one("SELECT payment_mode FROM boost_order WHERE id=?",id),"paymentMode"));
        Future<?> cancel=start("cancel-order",()->{payments.closeUnpaid(buyer,id);return null;});
        await(cancelStarted);allowContenderToFinishOrWait(cancel);wx.createGate.open();
        prepay.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS);
        assertEquals(Arrays.asList("create","query","close"),wx.events,"Remote creation must finish before cancellation closes it");
        assertFalse(wx.remoteOpen,"Cancelled local order must not leave a new payable remote order");
        assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));
    }
    @Test void uncertainPrepayKeepsIntentAndCanBeClosed(){
        long id=order();wx.timeout=true;
        assertEquals(503,assertThrows(BoostException.class,()->payments.prepay(buyer,id)).code);
        assertEquals("WECHAT",str(db.one("SELECT payment_mode FROM boost_order WHERE id=?",id),"paymentMode"));
        assertEquals("WAIT_PAY",str(trade.detail(id,buyer),"status"));
        assertTrue(wx.remoteOpen);payments.closeUnpaid(buyer,id);assertFalse(wx.remoteOpen);
        assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));
    }
    @Test void cancellationBetweenIntentAndRemoteRequestPreventsRemoteCreation()throws Exception{
        long id=order();Gate beforeSecondRead=gate();java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        // Pause before the second transaction acquires participant locks. The first
        // intent is committed; cancellation must finish before the remote request.
        db.beforeRead=s->{if(Thread.currentThread().getName().equals("prepay-between")&&s.startsWith("SELECT buyer_id,worker_id FROM boost_order WHERE id=?")&&reads.incrementAndGet()==2)beforeSecondRead.pause();};
        Future<?> prepay=start("prepay-between",()->payments.prepay(buyer,id));beforeSecondRead.reached();
        payments.closeUnpaid(buyer,id);beforeSecondRead.open();
        ExecutionException failure=assertThrows(ExecutionException.class,()->prepay.get(10,TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof BoostException);
        assertEquals(Arrays.asList("query","close"),wx.events);
        assertFalse(wx.remoteOpen);assertEquals("CANCELLED",str(trade.detail(id,buyer),"status"));
    }
}
