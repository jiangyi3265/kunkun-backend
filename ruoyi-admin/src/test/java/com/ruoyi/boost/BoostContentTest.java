package com.ruoyi.boost;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.boost.BoostDb.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=BoostContentTest.Config.class) @Transactional
class BoostContentTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource dataSource(){JdbcDataSource d=new JdbcDataSource();d.setURL("jdbc:h2:mem:content;MODE=MySQL;DB_CLOSE_DELAY=-1");BoostRewardsTest.Config.initialize(d);return d;}
  @Bean JdbcTemplate jdbc(DataSource d){return new JdbcTemplate(d);}
  @Bean PlatformTransactionManager tx(DataSource d){return new DataSourceTransactionManager(d);}
  @Bean BoostDb db(JdbcTemplate j){return new BoostDb(j);}
  @Bean BoostRewardsMedia media(BoostDb d){return new BoostRewardsMedia(d);}
  @Bean BoostContent content(BoostDb d,BoostRewardsMedia m){return new BoostContent(d,m);}
 }
 @Autowired BoostDb db;@Autowired BoostContent content;@Autowired BoostRewardsMedia media;
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;String image;
 @BeforeEach void prepare()throws Exception{ReflectionTestUtils.setField(media,"directory",directory.toString());java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,4,1),"png",b);image=str((Map<String,Object>)media.upload(1,new MockMultipartFile("file","public.png","image/png",b.toByteArray())),"url");}
 Map<String,Object> article(){return map("requestKey",UUID.randomUUID().toString(),"title","道具交流活动","summary","本周玩家活动","cover",image,"body","游戏内道具交流说明","detailImages",List.of(image),"startsAt","2020-01-01T00:00:00","endsAt","2030-01-01T00:00:00","status","PUBLISHED","rewardActivityId",null);}
 @Test void siteTextAndImagePersistWithRevisionAndRejectUnsafeTargets(){Map<String,Object>s=content.site();s.put("brand","新名称");s.put("banners",List.of(map("image",image,"target","content/activities","label","查看本周活动")));content.saveSite(1,s);assertEquals("新名称",str(content.site(),"brand"));assertEquals(1,num(content.site(),"revision"));assertEquals(409,assertThrows(BoostException.class,()->content.saveSite(1,s)).code);Map<String,Object>bad=content.site();bad.put("banners",List.of(map("image",image,"target","https://example.org","label","外链")));assertThrows(BoostException.class,()->content.saveSite(1,bad));assertEquals(1,num(content.site(),"revision"));}
 @Test void publishedActivitiesSortNewestAndExpiredRemainInHistory(){Map<String,Object>a=article();long first=num((Map<String,Object>)content.saveActivity(1,null,a),"id");long second=num((Map<String,Object>)content.saveActivity(1,null,article()),"id");List<Map<String,Object>> current=(List<Map<String,Object>>)((Map<String,Object>)content.activities(Map.of("scope","current"),false)).get("items");assertEquals(second,num(current.get(0),"id"));assertFalse(current.get(0).containsKey("createKey"));Map<String,Object>edit=content.activity(first,true);edit.put("endsAt","2021-01-01T00:00:00");content.saveActivity(1,first,edit);assertEquals("ENDED",str(content.activity(first,false),"phase"));List<Map<String,Object>> history=(List<Map<String,Object>>)((Map<String,Object>)content.activities(Map.of("scope","history"),false)).get("items");assertEquals(first,num(history.get(0),"id"));assertEquals(1,num((Map<String,Object>)content.activities(Map.of("scope","current"),false),"total"));}
 @Test void draftsAndOfflineArePrivateAndCreationRetriesAreIdempotent(){Map<String,Object>b=article();b.put("status","DRAFT");long id=num((Map<String,Object>)content.saveActivity(1,null,b),"id");assertEquals(id,num((Map<String,Object>)content.saveActivity(1,null,b),"id"));assertThrows(BoostException.class,()->content.activity(id,false));b.put("title","改变请求");assertEquals(409,assertThrows(BoostException.class,()->content.saveActivity(1,null,b)).code);Map<String,Object>edit=content.activity(id,true);edit.put("status","PUBLISHED");content.saveActivity(1,id,edit);Map<String,Object>stale=new LinkedHashMap<>(edit);assertEquals(409,assertThrows(BoostException.class,()->content.saveActivity(1,id,stale)).code);edit=content.activity(id,true);edit.put("status","OFFLINE");content.saveActivity(1,id,edit);assertThrows(BoostException.class,()->content.activity(id,false));}
 @Test void invalidImagesAndRewardLinksCannotPublish(){Map<String,Object>b=article();b.put("detailImages",List.of("https://external.example/image.png"));assertThrows(BoostException.class,()->content.saveActivity(1,null,b));b.put("detailImages",List.of(image));b.put("rewardActivityId",99999);assertThrows(BoostException.class,()->content.saveActivity(1,null,b));assertEquals(0,db.count("SELECT COUNT(*) FROM boost_content_activity"));b.put("rewardActivityId",null);db.run("UPDATE sys_user SET status='1' WHERE user_id=1");assertThrows(BoostException.class,()->content.saveActivity(1,null,b));}
}
