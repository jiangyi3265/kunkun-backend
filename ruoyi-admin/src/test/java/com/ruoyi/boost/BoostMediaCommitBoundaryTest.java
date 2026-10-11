package com.ruoyi.boost;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ruoyi.boost.BoostDb.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring proxies and private files; the total gate uses isolated MySQL, focused runs H2.
 * Controlled scheduling/faults do not replace persisted data. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=BoostMediaCommitBoundaryTest.Config.class)
class BoostMediaCommitBoundaryTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource source(){
            if(System.getenv("BOOST_TEST_DB_URL")!=null)return new BoostAbuseControlTest.Config().source();
            JdbcDataSource ds=new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:media_commit_boundaries;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
            new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(ds);
            BoostMigrations.migrate(new BoostDb(new JdbcTemplate(ds)));return ds;
        }
        @Bean ControlledDb db(DataSource ds){return new ControlledDb(new JdbcTemplate(ds));}
        @Bean PlatformTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean BoostAuth auth(BoostDb db){return new BoostAuth(db);}
        @Bean ControlledMedia media(BoostAuth auth,BoostDb db){return new ControlledMedia(auth,db);}
    }
    static class ControlledMedia extends BoostMediaFiles {
        volatile boolean failStagedDelete;
        ControlledMedia(BoostAuth auth,BoostDb db){super(auth,db,new BoostAccess(){@Override public boolean can(String permission){return false;}});}
        @Override protected void deleteStaged(Path staged)throws java.io.IOException{if(failStagedDelete)throw new java.io.IOException("Controlled committed-file deletion failure");super.deleteStaged(staged);}
    }
    static class ControlledDb extends BoostDb {
        volatile Consumer<String> afterRead=sql->{};
        volatile boolean failAfterDelete;
        ControlledDb(JdbcTemplate jdbc){super(jdbc);}
        @Override public List<Map<String,Object>> list(String sql,Object... args){List<Map<String,Object>> rows=super.list(sql,args);afterRead.accept(sql);return rows;}
        @Override public int run(String sql,Object... args){int result=super.run(sql,args);if(failAfterDelete&&sql.startsWith("DELETE FROM boost_media WHERE name=?"))throw new IllegalStateException("Controlled failure after actual media SQL delete");return result;}
    }
    @Autowired ControlledDb db;@Autowired BoostAuth auth;@Autowired ControlledMedia media;@Autowired PlatformTransactionManager tx;
    @TempDir Path directory;
    long member;String token;byte[] png;
    @BeforeEach void setup()throws Exception{
        db.afterRead=sql->{};db.failAfterDelete=false;ReflectionTestUtils.setField(media,"failStagedDelete",false);
        ReflectionTestUtils.setField(db,"mediaDirectory",directory.toString());ReflectionTestUtils.setField(media,"directory",directory.toString());
        Map<String,Object> account=auth.register(map("username","qa_commit_"+UUID.randomUUID().toString().replace("-","").substring(0,16),"password","MediaBoundary2026!","nickname","媒体事务边界"));
        member=num((Map<String,Object>)account.get("user"),"id");token=str(account,"token");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,1),"png",bytes);png=bytes.toByteArray();
    }
    @AfterEach void cleanup(){
        db.afterRead=sql->{};db.failAfterDelete=false;ReflectionTestUtils.setField(media,"failStagedDelete",false);
        db.run("DELETE FROM boost_media WHERE owner_id=?",member);db.run("DELETE FROM boost_notification WHERE member_id=?",member);
        db.run("DELETE FROM boost_session WHERE member_id=?",member);db.run("DELETE FROM boost_member WHERE id=?",member);
    }
    String upload()throws Exception{MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-Boost-Token",token);return str((Map<String,Object>)media.upload(new MockMultipartFile("file","proof.png","image/png",png),"CERTIFICATION",request),"url");}
    String agedAsset()throws Exception{String url=upload();db.run("UPDATE boost_media SET created_at=? WHERE name=?",Timestamp.from(Instant.now().minusSeconds(8*86400)),url.substring(13));return url.substring(13);}
    void intact(String name)throws Exception{assertEquals(1,db.count("SELECT COUNT(*) FROM boost_media WHERE name=?",name));assertTrue(Files.isRegularFile(directory.resolve(name)),"Rollback must preserve the registered image bytes");assertArrayEquals(png,Files.readAllBytes(directory.resolve(name)));}
    @Test void callerRollbackCannotLoseTheRegisteredMediaBytes()throws Exception{
        String name=agedAsset();new TransactionTemplate(tx).execute(status->{assertEquals(1,media.collectOrphans());status.setRollbackOnly();return null;});intact(name);
    }
    @Test void outerRepeatableReadCannotDeleteEvidenceAttachedAfterItsCandidateScan()throws Exception{
        String name=agedAsset(),url="/boost-media/"+name;CountDownLatch assetHeld=new CountDownLatch(1),candidateScanned=new CountDownLatch(1),bindingCommitted=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        db.afterRead=sql->{
            try{
                if(Thread.currentThread().getName().equals("binding-proof-after-scan")&&sql.startsWith("SELECT * FROM boost_media WHERE name=? FOR UPDATE")){assetHeld.countDown();assertTrue(candidateScanned.await(15,TimeUnit.SECONDS));}
                if(sql.startsWith("SELECT * FROM boost_media WHERE created_at<")){candidateScanned.countDown();assertTrue(bindingCommitted.await(15,TimeUnit.SECONDS));}
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        };
        try{
            Future<?> binding=pool.submit(()->{Thread.currentThread().setName("binding-proof-after-scan");try{auth.apply(member,map("skill","真实事务中附加隔离游戏能力证明","images",List.of(url)));return null;}finally{bindingCommitted.countDown();}});
            assertTrue(assetHeld.await(15,TimeUnit.SECONDS));TransactionTemplate outer=new TransactionTemplate(tx);outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            int deleted=outer.execute(status->media.collectOrphans());assertDoesNotThrow(()->binding.get(15,TimeUnit.SECONDS));
            assertTrue(str(db.one("SELECT proof FROM boost_member WHERE id=?",member),"proof").contains(url));assertEquals(0,deleted,"Outer snapshot must not hide a newly committed proof reference");intact(name);
        }finally{candidateScanned.countDown();bindingCommitted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));db.afterRead=sql->{};}
    }
    @Test void actualSqlDeleteFailureRollsBackMetadataAndFileTogether()throws Exception{
        String name=agedAsset();db.failAfterDelete=true;
        assertThrows(IllegalStateException.class,()->media.collectOrphans());db.failAfterDelete=false;intact(name);
    }
    @Test void successfulCleanupStillRemovesBothFileAndMetadata()throws Exception{
        String name=agedAsset();assertEquals(1,media.collectOrphans());assertEquals(0,db.count("SELECT COUNT(*) FROM boost_media WHERE name=?",name));assertFalse(Files.exists(directory.resolve(name)));
    }
    @Test void corruptDatabaseFilenameCannotMoveAFileOutsideTheMediaRoot()throws Exception{
        Path inner=directory.resolve("media"),outside=directory.resolve("outside.png");Files.createDirectories(inner);Files.write(outside,png);
        ReflectionTestUtils.setField(media,"directory",inner.toString());ReflectionTestUtils.setField(db,"mediaDirectory",inner.toString());
        db.run("INSERT INTO boost_media(name,owner_id,purpose,size_bytes,created_at) VALUES (?,?,?,?,?)","../outside.png",member,"CERTIFICATION",png.length,Timestamp.from(Instant.now().minusSeconds(8*86400)));
        assertEquals(0,media.collectOrphans());assertArrayEquals(png,Files.readAllBytes(outside));
        assertEquals(1,db.count("SELECT COUNT(*) FROM boost_media WHERE name=?","../outside.png"));
    }
    Path stageFixture(String name)throws Exception{
        Path trash=directory.resolve(".gc-trash");Files.createDirectories(trash);Path staged=trash.resolve(name+"."+UUID.randomUUID().toString().replace("-","")+".pending");Files.move(directory.resolve(name),staged,StandardCopyOption.ATOMIC_MOVE);return staged;
    }
    @Test void persistedStagingWithRegisteredEvidenceRecoversExactBytes()throws Exception{
        String name=agedAsset();db.run("UPDATE boost_member SET proof=? WHERE id=?",BoostInput.images(map("images",List.of("/boost-media/"+name))),member);
        Path staged=stageFixture(name);assertEquals(0,media.collectOrphans());intact(name);assertFalse(Files.exists(staged));
    }
    @Test void persistedStagingAfterCommittedDeletionIsCollected()throws Exception{
        String name=agedAsset();Path staged=stageFixture(name);db.run("DELETE FROM boost_media WHERE name=?",name);
        assertEquals(0,media.collectOrphans());assertFalse(Files.exists(staged));assertFalse(Files.exists(directory.resolve(name)));
    }
    @Test void postCommitFilesystemFailureRetainsBytesAndRetriesNextCollection()throws Exception{
        String name=agedAsset();ReflectionTestUtils.setField(media,"failStagedDelete",true);assertEquals(1,media.collectOrphans());
        assertEquals(0,db.count("SELECT COUNT(*) FROM boost_media WHERE name=?",name));assertFalse(Files.exists(directory.resolve(name)));
        List<Path> staged;try(var entries=Files.list(directory.resolve(".gc-trash"))){staged=entries.toList();}
        assertEquals(1,staged.size());assertArrayEquals(png,Files.readAllBytes(staged.get(0)));
        ReflectionTestUtils.setField(media,"failStagedDelete",false);assertEquals(0,media.collectOrphans());assertFalse(Files.exists(staged.get(0)));
    }
    @Test void uploadAfterAuthenticationCannotCommitForANowBlockedMember()throws Exception{
        CountDownLatch authenticated=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        db.afterRead=sql->{if(sql.startsWith("SELECT m.id FROM boost_session")){authenticated.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}};
        try{
            Future<?> upload=pool.submit(()->{try{return upload();}catch(Exception e){throw new CompletionException(e);}});assertTrue(authenticated.await(15,TimeUnit.SECONDS));
            new TransactionTemplate(tx).execute(status->{db.run("UPDATE boost_member SET status='BLOCKED' WHERE id=?",member);db.run("DELETE FROM boost_session WHERE member_id=?",member);return null;});
            release.countDown();ExecutionException failure=assertThrows(ExecutionException.class,()->upload.get(20,TimeUnit.SECONDS));
            Throwable cause=failure.getCause();if(cause instanceof CompletionException)cause=cause.getCause();assertInstanceOf(BoostException.class,cause);assertEquals(401,((BoostException)cause).code);
            assertEquals(0,db.count("SELECT COUNT(*) FROM boost_media WHERE owner_id=?",member));try(var files=Files.list(directory)){assertEquals(0,files.count());}
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));db.afterRead=sql->{};}
    }
}
