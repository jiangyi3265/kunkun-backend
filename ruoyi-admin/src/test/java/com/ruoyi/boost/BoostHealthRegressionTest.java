package com.ruoyi.boost;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BoostHealthRegressionTest {
 @Test void incompleteSchemaCannotPassWithSelectOne(){
  BoostDb db=mock(BoostDb.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);
  when(db.count(anyString(),any(Object[].class))).thenAnswer(i->{if(i.getArgument(0,String.class).contains("boost_order"))throw new IllegalStateException("fixture table unavailable");return 1L;});
  when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
  assertEquals(503,new BoostHealth(db,redis).ready().getStatusCode().value());
 }
 @Test void missingPongIsNotHealthy(){
  BoostDb db=mock(BoostDb.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);
  when(redis.execute(any(RedisCallback.class))).thenReturn(null);
  assertEquals(503,new BoostHealth(db,redis).ready().getStatusCode().value());
 }
 @Test void readyOnlyWithBusinessSchemaAndPong(){
  BoostDb db=mock(BoostDb.class);StringRedisTemplate redis=mock(StringRedisTemplate.class);
  when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
  assertEquals(200,new BoostHealth(db,redis).ready().getStatusCode().value());
  verify(db).count(contains("boost_order"),any(Object[].class));
 }
}
