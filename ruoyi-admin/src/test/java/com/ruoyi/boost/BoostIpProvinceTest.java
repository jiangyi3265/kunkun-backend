package com.ruoyi.boost;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class BoostIpProvinceTest {
 @Test void offlineDataResolvesProvinceForBothNumericAddressFamilies(){BoostIpProvince p=new BoostIpProvince();assertEquals("广东",p.lookup("113.92.157.29"));assertEquals("境外",p.lookup("2606:4700:4700::1111"));assertEquals("境外",p.lookup("8.8.8.8"));}
 @Test void privateInvalidAndHostnameInputsNeverBecomePublicProvince(){BoostIpProvince p=new BoostIpProvince();for(String ip:new String[]{"127.0.0.1","10.0.0.2","192.168.2.1","::1","fd00::1","localhost","1.999.2.3","1234","2130706433","1.2.3.4,8.8.8.8",""})assertEquals("未知",p.lookup(ip),ip);}
 @Test void forwardedHeaderCannotOverrideAnUntrustedPeer(){BoostIpProvince p=new BoostIpProvince();MockHttpServletRequest r=new MockHttpServletRequest();r.setRemoteAddr("8.8.8.8");r.addHeader("X-Forwarded-For","113.92.157.29");assertEquals("8.8.8.8",p.clientIp(r));assertEquals("境外",p.province(r));ReflectionTestUtils.setField(p,"proxies","127.0.0.1,::1");r.setRemoteAddr("127.0.0.1");assertEquals("广东",p.province(r));r.removeHeader("X-Forwarded-For");r.addHeader("X-Forwarded-For","113.92.157.29,8.8.8.8");assertEquals("未知",p.province(r));}
}
