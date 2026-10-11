package com.ruoyi.boost;

import java.net.InetAddress;
import java.util.*;
import java.io.InputStream;
import java.nio.file.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;

/** Offline, province-only lookup. Forwarded headers are accepted only from configured peers. */
@Component
public class BoostIpProvince {
    @Value("${boost.ip-trusted-proxies:}") private String proxies="";
    private record Database(Path file,byte[] index){}
    private static Database v4,v6;
    private static final Set<String> PROVINCES=Set.of("北京","天津","上海","重庆","河北","山西","辽宁","吉林","黑龙江","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","海南","四川","贵州","云南","陕西","甘肃","青海","台湾","内蒙古","广西","西藏","宁夏","新疆","香港","澳门");
    public String province(HttpServletRequest request){return lookup(clientIp(request));}
    String clientIp(HttpServletRequest request){
        String peer=request.getRemoteAddr();
        if(Arrays.stream(proxies.split(",")).map(String::trim).anyMatch(peer::equals)){
            String forwarded=request.getHeader("X-Forwarded-For");
            // Our deployment proxy overwrites this header with exactly one remote address.
            if(forwarded!=null&&!forwarded.contains(","))return forwarded.trim();
        }
        return peer;
    }
    public String lookup(String ip){
        try{
            if(ip==null||ip.length()>45||!(ip.matches("[0-9.]+")||ip.matches("[a-fA-F0-9:]+")))return "未知";
            if(!ip.contains(":")){
                if(!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return "未知";
                for(String part:ip.split("\\."))if(Integer.parseInt(part)>255)return "未知";
            }
            InetAddress address=InetAddress.getByName(ip);
            if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())return "未知";
            byte[] numeric=address.getAddress();if(numeric.length==16&&(numeric[0]&0xfe)==0xfc)return "未知";
            boolean ipv6=address.getAddress().length==16;Database data=database(ipv6);
            String region;Searcher reader=Searcher.newWithVectorIndex(ipv6?Version.IPv6:Version.IPv4,data.file().toFile(),data.index());
            try{region=reader.search(address.getAddress());}finally{reader.close();}
            if(region==null||region.isBlank())return "未知";
            for(String field:region.split("\\|")){
                String p=field.replaceFirst("(省|市|壮族自治区|回族自治区|维吾尔自治区|自治区)$","");
                if(PROVINCES.contains(p))return p;
            }
            return region.startsWith("中国|")?"未知":"境外";
        }catch(Exception unavailable){return "未知";}
    }
    private static synchronized Database database(boolean ipv6)throws Exception{
        Database existing=ipv6?v6:v4;if(existing!=null)return existing;
        String file="boost/ip-region/ip2region_"+(ipv6?"v6":"v4")+".xdb";
        try(InputStream input=new ClassPathResource(file).getInputStream()){
            Path extracted=Files.createTempFile("boost-province-"+(ipv6?"v6":"v4"),".xdb");extracted.toFile().deleteOnExit();
            Files.copy(input,extracted,StandardCopyOption.REPLACE_EXISTING);Searcher.verifyFromFile(extracted.toFile());
            Database result=new Database(extracted,Searcher.loadVectorIndexFromFile(extracted.toFile()));
            if(ipv6)v6=result;else v4=result;return result;
        }
    }
}
