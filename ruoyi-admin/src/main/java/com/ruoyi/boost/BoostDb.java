package com.ruoyi.boost;

import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;

/** Small parameterized JDBC adapter. Monetary values are integer fen everywhere. */
@Repository
public class BoostDb {
    private final JdbcTemplate jdbc;
    @Value("${boost.media-dir:./.runtime/boost-media}") private String mediaDirectory;
    public BoostDb(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JdbcTemplate template() { return jdbc; }
    public List<Map<String,Object>> list(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> {
            Map<String,Object> m = new LinkedHashMap<>();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i=1; i<=meta.getColumnCount(); i++) {
                String[] parts = meta.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
                StringBuilder name = new StringBuilder(parts[0]);
                for (int j=1; j<parts.length; j++) name.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
                Object v = rs.getObject(i);
                if (v instanceof Clob) v = ((Clob)v).getSubString(1,(int)((Clob)v).length());
                if (v instanceof Timestamp) v = ((Timestamp)v).toLocalDateTime().toString();
                m.put(name.toString(), v);
            }
            return m;
        }, args);
    }
    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> r=list(sql,args);
        if (r.isEmpty()) throw new BoostException(404,"内容不存在或已被移除");
        return r.get(0);
    }
    public long count(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0:n; }
    public int run(String sql,Object... args) { return jdbc.update(sql,args); }
    /** Recheck the actor using a current locking read inside its owning service transaction.
     * Member locks precede assets/listings/conversations so suspension and writes serialize. */
    public Map<String,Object> activeMember(long member) {
        List<Map<String,Object>> rows=list("SELECT id,status FROM boost_member WHERE id=? FOR UPDATE",member);
        if(rows.isEmpty()||!"ACTIVE".equals(str(rows.get(0),"status")))throw new BoostException(401,"登录已失效，请重新登录");
        return rows.get(0);
    }
    public void uploadedImage(String url,long member,String purpose) {
        BoostException.require(url.matches("/boost-media/[a-f0-9]{32}\\.(png|jpg|webp)"),"请先上传图片");
        List<Map<String,Object>> assets=list("SELECT * FROM boost_media WHERE name=? FOR UPDATE",url.substring("/boost-media/".length()));
        boolean permitted=!assets.isEmpty()&&num(assets.get(0),"ownerId")==member&&purpose.equals(str(assets.get(0),"purpose"));
        // Preserve already-attached legacy files. An unregistered URL cannot introduce new ownership.
        if(assets.isEmpty()){
            String match="%\""+url+"\"%";
            if("LISTING".equals(purpose))permitted=count("SELECT COUNT(*) FROM boost_listing WHERE owner_id=? AND images LIKE ?",member,match)>0;
            else if("CERTIFICATION".equals(purpose))permitted=count("SELECT COUNT(*) FROM boost_member WHERE id=? AND proof LIKE ?",member,match)>0;
            else if("AVATAR".equals(purpose))permitted=count("SELECT COUNT(*) FROM boost_member WHERE id=? AND avatar=?",member,url)>0;
            else if("DELIVERY".equals(purpose))permitted=count("SELECT COUNT(*) FROM boost_progress p JOIN boost_order o ON o.id=p.order_id WHERE o.worker_id=? AND p.images LIKE ?",member,match)>0;
            else if("AFTERSALE".equals(purpose))permitted=count("SELECT COUNT(*) FROM boost_aftersale WHERE member_id=? AND images LIKE ?",member,match)>0;
            else if("CHAT".equals(purpose))permitted=count("SELECT COUNT(*) FROM boost_message WHERE sender_id=? AND kind='IMAGE' AND content=?",member,url)>0;
        }
        if(!permitted)throw new BoostException(403,"图片不属于当前账号或不适用于此用途，请重新上传");
        Path root=Paths.get(mediaDirectory).toAbsolutePath().normalize();
        Path file=root.resolve(url.substring("/boost-media/".length())).normalize();
        BoostException.require(file.startsWith(root)&&Files.isRegularFile(file),"图片不存在，请重新上传");
    }
    public String uploadedImages(Map<String,Object> body,long member,String purpose) {
        String json=BoostInput.images(body);
        if(body.get("images")!=null)for(Object value:(List<?>)body.get("images"))uploadedImage((String)value,member,purpose);
        return json;
    }
    public long insert(String sql,Object... args) {
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(c -> { PreparedStatement p=c.prepareStatement(sql,new String[]{"id"}); for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]); return p; },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    public static long num(Map<String,Object> m,String key) { Object v=m.get(key); return v==null?0:((Number)v).longValue(); }
    public static String str(Map<String,Object> m,String key) { Object v=m.get(key); return v==null?"":v.toString(); }
    public static Map<String,Object> map(Object... kv) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<kv.length;i+=2)m.put(kv[i].toString(),kv[i+1]); return m; }
    public void notify(long member,String title,String content,Long order) {
        run("INSERT INTO boost_notification(member_id,title,content,order_id) VALUES (?,?,?,?)",member,preview(title,100),preview(content,500),order);
    }
    private static String preview(String value,int limit) {
        if(value.length()<=limit)return value;
        int end=limit-1;
        if(Character.isHighSurrogate(value.charAt(end-1)))end--;
        return value.substring(0,end)+"…";
    }
}
