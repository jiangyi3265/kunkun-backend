package com.ruoyi.boost;

import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static com.ruoyi.boost.BoostDb.*;

@Component @DependsOn("boostBootstrap")
public class BoostProductionGuard {
    private final Environment env;private final BoostDb db;private final BoostWechatGateway wx;private final BoostPolicy policy;
    public BoostProductionGuard(Environment e,BoostDb d,BoostWechatGateway w,BoostPolicy p){env=e;db=d;wx=w;policy=p;}
    private String value(String key){return env.getProperty(key,"").trim();}
    private void require(boolean ok,String reason){if(!ok)throw new IllegalStateException("Production validation failed: "+reason);}
    @jakarta.annotation.PostConstruct public void validate()throws Exception{
        boolean prod=Arrays.asList(env.getActiveProfiles()).contains("prod"),live=env.getProperty("boost.live",Boolean.class,false);
        require(!prod||live,"prod must enable boost.live");
        boolean paymentsEnabled=env.getProperty("boost.payments-enabled",Boolean.class,true);
        require(!paymentsEnabled||live||env.getProperty("boost.simulated-payment",Boolean.class,true),"real payment mode must enable production validation");if(!live)return;
        for(String key:new String[]{"boost.demo","boost.simulated-payment","boost.initialize-schema","spring.datasource.druid.statViewServlet.enabled","swagger.enabled"})require(!env.getProperty(key,Boolean.class,false),key+" must be false");
        String user=value("spring.datasource.druid.master.username");require(!user.isEmpty()&&!"root".equalsIgnoreCase(user),"use a dedicated database account");
        require(value("spring.datasource.druid.master.url").matches(".*[?&](connectionTimeZone|serverTimezone)=UTC(&.*)?")&&value("spring.datasource.druid.master.url").contains("forceConnectionTimeZoneToSession=true"),"JDBC and database session time zones must both use UTC");
        require(db.count("SELECT ABS(TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),CURRENT_TIMESTAMP))")==0,"database session must use UTC");
        require(value("spring.datasource.druid.master.password").length()>=16,"database password is missing or too short");
        require(value("token.secret").length()>=64,"JWT signing key must contain at least 64 characters");
        require(value("boost.media-signing-key").length()>=48,"media signing key must contain at least 48 characters");
        require(value("spring.redis.password").length()>=16||value("spring.data.redis.password").length()>=16,"Redis authentication is required");
        URI callback=URI.create(value("boost.wechat.notify-base"));require("https".equals(callback.getScheme())&&callback.getHost()!=null&&!callback.getHost().equals("localhost")&&!callback.getHost().matches("[0-9.]+")&&!callback.getHost().endsWith(".invalid"),"set a real HTTPS callback domain");
        require(Boolean.TRUE.equals(policy.document().get("ready")),"operator, privacy contact, retention, policy version and effective date are required");
        require(value("boost.policy.contact").matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"),"set the operator privacy email");java.time.LocalDate.parse(value("boost.policy.effective-date"));
        require(value("boost.wechat.app-id").matches("wx[a-fA-F0-9]{16}"),"set the production mini-program AppID");
        if(paymentsEnabled){wx.ensureConfigured();require(!value("boost.wechat.transfer-scene-id").isEmpty(),"configure the approved transfer scene");wx.transferReports();}
        require(!value("boost.wechat.app-secret").isEmpty(),"mini-program secret is required");
        String database=str(db.one("SELECT DATABASE() AS name"),"name");require(!Arrays.asList("ha","test","mysql","sys","information_schema","performance_schema").contains(database.toLowerCase(Locale.ROOT)),"use an isolated production database");
        BoostMigrations.verifyApplied(db);
        if(!paymentsEnabled)verifyNoFinancialObligations(db);
        require(db.count("SELECT COUNT(*) FROM boost_member WHERE username IN ('demo_buyer','demo_worker')")==0&&db.count("SELECT COUNT(*) FROM boost_order WHERE payment_mode='SIMULATED'")==0,"test identities or simulated transactions exist in this database");
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();for(Map<String,Object> admin:db.list("SELECT password FROM sys_user WHERE status='0' AND del_flag='0'")){String hash=str(admin,"password");for(String weak:new String[]{"admin123","123456","password","Demo2026!"})require(!encoder.matches(weak,hash),"an operator still uses a known default password");}
        for(Map<String,Object> grant:db.list("SHOW GRANTS FOR CURRENT_USER"))for(Object raw:grant.values())require(scopedGrant(raw.toString(),database),"runtime database account must have only scoped DML privileges");
        for(String key:new String[]{"boost.media-dir","ruoyi.profile"}){require(!value(key).isEmpty(),key+" must be configured");Path dir=Paths.get(value(key)).toAbsolutePath();Files.createDirectories(dir);require(Files.isWritable(dir),key+" must be writable");}
        Path media=Paths.get(value("boost.media-dir")).toRealPath(),profile=Paths.get(value("ruoyi.profile")).toRealPath();
        require(!media.startsWith(profile),"private media must be outside the public profile directory");
        require(!value("boost.web-origins").isEmpty(),"configure exact HTTPS web origins");
        for(String origin:value("boost.web-origins").split(","))require(validOrigin(origin.trim()),"web origins must be exact HTTPS origins without wildcards");
    }
    static void verifyNoFinancialObligations(BoostDb db){
        if(db.count("SELECT COUNT(*) FROM boost_order WHERE status IN ('WAIT_DELIVERY','WAIT_CONFIRM','WAIT_START','IN_PROGRESS','REVIEW','AFTERSALE','REFUNDING','REFUND_EXCEPTION') OR (status='WAIT_PAY' AND payment_mode='WECHAT')")>0
            ||db.count("SELECT COUNT(*) FROM boost_withdrawal WHERE status IN ('PENDING','PROCESSING','WAIT_USER_CONFIRM')")>0
            ||db.count("SELECT COUNT(*) FROM boost_member WHERE balance_cents<>0")>0)
            throw new IllegalStateException("Production validation failed: disabled payments cannot strand active financial obligations");
    }
    static boolean scopedGrant(String grant,String database){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^GRANT (.+) ON (.+) TO .+$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(grant);
        if(!m.matches()||grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"))return false;
        String privileges=m.group(1).toUpperCase(Locale.ROOT),scope=m.group(2);
        if("USAGE".equals(privileges))return "*.*".equals(scope);
        if(!scope.equals("`"+database.replace("`","``")+"`.*"))return false;
        for(String p:privileges.split(","))if(!Arrays.asList("SELECT","INSERT","UPDATE","DELETE").contains(p.trim()))return false;
        return true;
    }
    static boolean validOrigin(String value){try{URI u=URI.create(value);return "https".equals(u.getScheme())&&u.getHost()!=null&&!u.getHost().equals("localhost")&&!u.getHost().endsWith(".invalid")&&!u.getHost().matches("[0-9.]+")&&!value.contains("*")&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null&&(u.getPath()==null||u.getPath().isEmpty());}catch(Exception e){return false;}}
}
