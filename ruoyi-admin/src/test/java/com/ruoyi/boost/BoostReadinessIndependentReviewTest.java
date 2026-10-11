package com.ruoyi.boost;

import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real schema SQL on a disposable H2 database; Redis replies/errors are explicit test doubles. */
class BoostReadinessIndependentReviewTest {
 @Test void realSchemaQueryRejectsMissingTablesAndRedisFailuresButAcceptsAnEmptyUsableSchema(){
  JdbcDataSource source=new JdbcDataSource();
  source.setURL("jdbc:h2:mem:readiness_review_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1");
  JdbcTemplate jdbc=new JdbcTemplate(source);BoostDb db=new BoostDb(jdbc);
  try{
   new ResourceDatabasePopulator(new ClassPathResource("boost/schema.sql")).execute(source);BoostMigrations.migrate(db);
   StringRedisTemplate redis=mock(StringRedisTemplate.class);
   when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
   BoostHealth health=new BoostHealth(db,redis);
   assertEquals(1,db.count("SELECT 1"));
   assertEquals(503,health.ready().getStatusCode().value(),"Boost tables without the required admin table are not ready");
   jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY)");
   assertEquals(0,db.count("SELECT COUNT(*) FROM boost_member"));
   assertEquals(200,health.ready().getStatusCode().value(),"A valid empty schema must not need invented seed business rows");
   for(String reply:new String[]{null,"","not-PONG"}){
    when(redis.execute(any(RedisCallback.class))).thenReturn(reply);
    assertEquals(503,health.ready().getStatusCode().value(),"Redis reply: "+reply);
   }
   when(redis.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("isolated Redis failure"));
   assertEquals(503,health.ready().getStatusCode().value());
   reset(redis);when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
   jdbc.execute("DROP TABLE boost_media");
   assertEquals(1,db.count("SELECT 1"));
   assertEquals(503,health.ready().getStatusCode().value(),"SELECT 1 still succeeds after a required business table disappears");
  }finally{jdbc.execute("SHUTDOWN");}
 }
}
