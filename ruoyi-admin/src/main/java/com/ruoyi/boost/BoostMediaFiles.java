package com.ruoyi.boost;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static com.ruoyi.boost.BoostDb.*;

/** Public listing images and private evidence have different authorization rules. */
@Service
public class BoostMediaFiles {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    private final BoostAuth auth;private final BoostDb db;private final BoostAccess access;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Value("${boost.media-dir:./.runtime/boost-media}") private String directory;
    @Value("${boost.media-signing-key:}") private String signingKey="";
    @Value("${boost.media-quota-bytes:104857600}") private long quota=104857600;
    @Value("${boost.media-hourly-limit:60}") private int hourlyLimit=60;
    private final String processSecret=UUID.randomUUID().toString()+UUID.randomUUID();
    public BoostMediaFiles(BoostAuth a,BoostDb d,BoostAccess p){auth=a;db=d;access=p;}
    private Path root(){return Paths.get(directory).toAbsolutePath().normalize();}
    private String name(String url){
        BoostException.require(url!=null&&url.matches("/boost-media/[a-f0-9]{32}\\.(png|jpg|webp)"),"图片地址无效");
        return url.substring("/boost-media/".length());
    }
    // Authentication reads before the member lock; quota reads must see uploads committed while waiting.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Object upload(MultipartFile file,String purpose,HttpServletRequest request)throws IOException {
        long member=auth.member(request);
        db.activeMember(member);
        if("SHOP_CERTIFICATION".equals(purpose))BoostException.require(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=? AND (shop_status<>'NONE' OR shop_submitted_at IS NOT NULL)",member)==0,"店铺材料已提交，如需更改请联系后台");
        return uploadFor(file,purpose,member);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Object uploadShopProof(long member,long operator,MultipartFile file)throws IOException {
        if(!access.can("boost:shops:review"))throw new BoostException(403,"无店铺材料修改权限");
        db.activeMember(member);
        BoostException.require(db.count("SELECT COUNT(*) FROM boost_player_profile WHERE member_id=? AND (shop_status<>'NONE' OR shop_submitted_at IS NOT NULL)",member)>0,"用户尚未提交店铺材料");
        Object result=uploadFor(file,"SHOP_CERTIFICATION",member);
        db.run("INSERT INTO boost_audit(admin_id,action,target_id,note) VALUES (?,'shop:proof-upload',?,'后台上传店铺更正材料')",operator,member);
        return result;
    }
    private Object uploadFor(MultipartFile file,String purpose,long member)throws IOException {
        BoostException.require(Arrays.asList("LISTING","CERTIFICATION","SHOP_CERTIFICATION","DELIVERY","AFTERSALE","CHAT","AVATAR").contains(purpose),"请选择正确的图片用途");
        BoostException.require(file.getSize()>0&&file.getSize()<=5*1024*1024,"请选择 5MB 内的图片");
        List<Map<String,Object>> lockedMember=db.list("SELECT status FROM boost_member WHERE id=? FOR UPDATE",member);
        if(lockedMember.isEmpty()||!"ACTIVE".equals(str(lockedMember.get(0),"status")))throw new BoostException(401,"账号已停用或登录已失效，请重新登录");
        if(db.count("SELECT COUNT(*) FROM boost_media WHERE owner_id=? AND created_at>?",member,java.sql.Timestamp.from(Instant.now().minusSeconds(3600)))>=hourlyLimit)throw new BoostException(429,"上传过于频繁，请稍后再试");
        long used=db.count("SELECT COALESCE(SUM(size_bytes),0) FROM boost_media WHERE owner_id=?",member);
        if(used>=quota)throw new BoostException(429,"图片空间已满，请联系平台处理历史材料");
        BufferedImage picture;
        try(javax.imageio.stream.ImageInputStream stream=ImageIO.createImageInputStream(file.getInputStream())){
            Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(stream);
            BoostException.require(readers.hasNext(),"图片格式不支持，请使用 JPG 或 PNG 图片");
            javax.imageio.ImageReader reader=readers.next();
            try{reader.setInput(stream);BoostException.require((long)reader.getWidth(0)*reader.getHeight(0)<=16000000,"图片尺寸过大，请压缩后上传");picture=reader.read(0);}finally{reader.dispose();}
        }catch(IOException e){throw new BoostException(400,"图片无法读取，请重新选择 JPG 或 PNG 图片");}
        BoostException.require(picture!=null,"图片无法读取，请重新上传");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(picture,"png",bytes);
        BoostException.require(bytes.size()<=10*1024*1024&&used+bytes.size()<=quota,"图片转换后过大或空间不足，请压缩后上传");
        String name=UUID.randomUUID().toString().replace("-","")+".png";Files.createDirectories(root());Path target=root().resolve(name);
        Files.write(target,bytes.toByteArray(),StandardOpenOption.CREATE_NEW);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){}}
        });
        db.run("INSERT INTO boost_media(name,owner_id,purpose,size_bytes) VALUES (?,?,?,?)",name,member,purpose,bytes.size());
        return map("url","/boost-media/"+name);
    }
    private boolean referenced(String table,String field,String url,String condition,Object... args){
        List<Object> p=new ArrayList<>();p.add("%\""+url+"\"%");p.addAll(Arrays.asList(args));
        return db.count("SELECT COUNT(*) FROM "+table+" WHERE "+field+" LIKE ? AND ("+condition+")",p.toArray())>0;
    }
    private boolean legacyPrivate(String url){
        return (player!=null&&(referenced("boost_player_profile","shop_proof",url,"1=1")||referenced("boost_shop_revision","shop_proof",url,"1=1")||referenced("boost_item_delivery","images",url,"1=1")))||referenced("boost_member","proof",url,"1=1")||referenced("boost_progress","images",url,"1=1")||referenced("boost_aftersale","images",url,"1=1")||db.count("SELECT COUNT(*) FROM boost_message WHERE kind='IMAGE' AND content=?",url)>0;
    }
    private boolean publiclyReadable(String url,Map<String,Object> asset){
        if(asset==null&&legacyPrivate(url))return false;
        if(asset!=null&&!Arrays.asList("LISTING","AVATAR").contains(str(asset,"purpose")))return false;
        if(player!=null&&(asset==null||"LISTING".equals(str(asset,"purpose")))&&referenced("boost_listing l JOIN boost_member m ON m.id=l.owner_id","l.detail_images",url,"l.status='ACTIVE' AND m.status='ACTIVE'"))return true;
        if((asset==null||"LISTING".equals(str(asset,"purpose")))&&referenced("boost_listing l JOIN boost_member m ON m.id=l.owner_id","l.images",url,"l.status='ACTIVE' AND m.status='ACTIVE'"))return true;
        return (asset==null||"AVATAR".equals(str(asset,"purpose")))&&db.count("SELECT COUNT(*) FROM boost_member WHERE avatar=? AND status='ACTIVE'",url)>0;
    }
    private Map<String,Object> asset(String name){List<Map<String,Object>> a=db.list("SELECT * FROM boost_media WHERE name=?",name);return a.isEmpty()?null:a.get(0);}
    private boolean readable(String url,Long member,Map<String,Object> asset){
        if(publiclyReadable(url,asset))return true;
        if(asset!=null&&access.media(str(asset,"purpose")))return true;
        if(asset==null){
            if(player!=null&&access.media("SHOP_CERTIFICATION")&&referenced("boost_player_profile","shop_proof",url,"1=1"))return true;
            if(player!=null&&access.media("DELIVERY")&&referenced("boost_item_delivery","images",url,"1=1"))return true;
            if(access.media("CERTIFICATION")&&referenced("boost_member","proof",url,"1=1"))return true;
            if(access.media("DELIVERY")&&referenced("boost_progress","images",url,"1=1"))return true;
            if(access.media("AFTERSALE")&&referenced("boost_aftersale","images",url,"1=1"))return true;
            if(access.media("LISTING")&&!legacyPrivate(url)&&(referenced("boost_listing","images",url,"1=1")||(player!=null&&referenced("boost_listing","detail_images",url,"1=1"))))return true;
            if(player!=null&&access.media("DELIVERY")&&referenced("boost_order","detail_images",url,"1=1"))return true;
        }
        if(member==null)return false;
        if(asset!=null&&num(asset,"ownerId")==member)return true;
        if(player!=null&&referenced("boost_item_delivery p JOIN boost_order o ON o.id=p.order_id","p.images",url,"o.buyer_id=? OR o.worker_id=?",member,member))return true;
        if(referenced("boost_member","proof",url,"id=?",member))return true;
        if(referenced("boost_listing","images",url,"owner_id=?",member))return true;
        if(referenced("boost_order","snapshot_images",url,"buyer_id=? OR worker_id=?",member,member))return true;
        if(player!=null&&(referenced("boost_listing","detail_images",url,"owner_id=?",member)||referenced("boost_order","detail_images",url,"buyer_id=? OR worker_id=?",member,member)))return true;
        for(String t:new String[]{"boost_progress","boost_aftersale"})if(referenced(t+" p JOIN boost_order o ON o.id=p.order_id","p.images",url,"o.buyer_id=? OR o.worker_id=?",member,member))return true;
        return db.count("SELECT COUNT(*) FROM boost_message m JOIN boost_conversation c ON c.id=m.conversation_id WHERE m.kind='IMAGE' AND m.content=? AND (c.member_a=? OR c.member_b=?)",url,member,member)>0;
    }
    public Object authorize(Map<String,Object> body,HttpServletRequest request){
        BoostException.require(body.get("paths") instanceof List,"图片列表格式无效");List<?> paths=(List<?>)body.get("paths");BoostException.require(paths.size()<=100,"每次最多加载 100 张图片");
        Long member=auth.optional(request);Map<String,Object> urls=new LinkedHashMap<>();long expires=Instant.now().getEpochSecond()+120;
        for(Object value:paths){BoostException.require(value instanceof String,"图片地址格式无效");String url=(String)value,n=name(url);
            if(Files.isRegularFile(root().resolve(n))&&readable(url,member,asset(n))){String actor=member==null?"authorized":String.valueOf(member);urls.put(url,url+"?expires="+expires+"&actor="+actor+"&signature="+sign(n+"\n"+expires+"\n"+actor));}
        }
        return map("urls",urls,"expiresAt",expires);
    }
    private String sign(String value){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec((signingKey.isEmpty()?processSecret:signingKey).getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public ResponseEntity<Resource> media(String name,long expires,String actor,String signature)throws IOException{
        String url="/boost-media/"+name;name(url);Path file=root().resolve(name);
        if(!Files.isRegularFile(file))throw new BoostException(404,"图片不存在");
        boolean publicFile=publiclyReadable(url,asset(name));long now=Instant.now().getEpochSecond();
        boolean granted=expires>=now&&expires<=now+120&&MessageDigest.isEqual(sign(name+"\n"+expires+"\n"+actor).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
        if(!publicFile&&!granted)throw new BoostException(403,"图片需要授权或访问链接已过期");
        if(!publicFile&&actor.matches("[0-9]+")&&db.count("SELECT COUNT(*) FROM boost_member WHERE id=? AND status='ACTIVE'",Long.parseLong(actor))!=1)throw new BoostException(403,"图片访问权限已失效");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").cacheControl(publicFile?CacheControl.maxAge(60,java.util.concurrent.TimeUnit.SECONDS):CacheControl.noStore()).body(new FileSystemResource(file));
    }
    // After locking each asset, see references committed since the candidate scan.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public int collectOrphans(){
        recoverStagedFiles();
        List<Map<String,Object>> files=db.list("SELECT * FROM boost_media WHERE created_at<? ORDER BY gc_checked_at,created_at LIMIT 100",java.sql.Timestamp.from(Instant.now().minusSeconds(7*86400)));
        int deleted=0;
        for(Map<String,Object> f:files){
            String n=str(f,"name"),url="/boost-media/"+n;
            if(!n.matches("[a-f0-9]{32}\\.(png|jpg|webp)")){
                org.slf4j.LoggerFactory.getLogger(BoostMediaFiles.class).warn("Skipping invalid media cleanup filename");continue;
            }
            if(db.list("SELECT name FROM boost_media WHERE name=? FOR UPDATE",n).isEmpty())continue;
            boolean linked=linked(url);
            db.run("UPDATE boost_media SET gc_checked_at=CURRENT_TIMESTAMP WHERE name=?",n);
            if(!linked)try{
                Path original=root().resolve(n),staged=null;
                if(Files.exists(original)){
                    Path trash=root().resolve(".gc-trash");Files.createDirectories(trash);
                    staged=trash.resolve(n+"."+UUID.randomUUID().toString().replace("-","")+".pending");
                    // Same-directory-tree atomic rename preserves bytes until the database outcome is known.
                    Files.move(original,staged,StandardCopyOption.ATOMIC_MOVE);
                }
                if(staged!=null){
                    Path pending=staged;
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void afterCompletion(int status){
                            try{
                                if(status==STATUS_COMMITTED)deleteStaged(pending);
                                else if(status==STATUS_ROLLED_BACK)restoreStaged(pending,original);
                                // Unknown outcome retains the staged bytes for the next database-backed recovery.
                            }catch(IOException e){org.slf4j.LoggerFactory.getLogger(BoostMediaFiles.class).warn("Staged media requires recovery for {}",n);}
                        }
                    });
                }
                db.run("DELETE FROM boost_media WHERE name=?",n);deleted++;
            }
            catch(IOException e){org.slf4j.LoggerFactory.getLogger(BoostMediaFiles.class).warn("Media cleanup requires retry for {}",n);}
        }return deleted;
    }
    private boolean linkedInSnapshot(String url){return (player!=null&&(referenced("boost_listing","detail_images",url,"1=1")||referenced("boost_order","detail_images",url,"1=1")))||legacyPrivate(url)||referenced("boost_listing","images",url,"1=1")||referenced("boost_order","snapshot_images",url,"1=1")||db.count("SELECT COUNT(*) FROM boost_member WHERE avatar=?",url)>0;}
    private boolean linked(String url){
        // Keep this transaction's own uncommitted references as well as new committed references.
        if(linkedInSnapshot(url))return true;
        Integer isolation=org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if(isolation!=null&&isolation==java.sql.Connection.TRANSACTION_READ_COMMITTED)return false;
        // REQUIRED can join an outer repeatable-read snapshot. The held asset lock prevents
        // new attachments, while a separate read-only transaction sees attachments committed
        // before that lock was acquired. No reference/member locks are taken in reverse order.
        org.springframework.transaction.support.TransactionTemplate fresh=new org.springframework.transaction.support.TransactionTemplate(transactions);
        fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);fresh.setReadOnly(true);
        return Boolean.TRUE.equals(fresh.execute(status->linkedInSnapshot(url)));
    }
    protected void deleteStaged(Path staged)throws IOException{Files.deleteIfExists(staged);}
    private void restoreStaged(Path staged,Path original)throws IOException{
        if(!Files.exists(staged))return;
        if(Files.exists(original)){
            if(Files.mismatch(staged,original)!=-1)throw new IOException("Media recovery target differs; preserving both copies");
            deleteStaged(staged);
        }else Files.move(staged,original,StandardCopyOption.ATOMIC_MOVE);
    }
    // A process exit or a filesystem error after SQL completion must leave recoverable bytes.
    private void recoverStagedFiles(){
        Path trash=root().resolve(".gc-trash");if(!Files.isDirectory(trash))return;
        int checked=0;
        try(DirectoryStream<Path> pending=Files.newDirectoryStream(trash)){
            for(Path staged:pending){
                if(checked>=100)break;
                java.util.regex.Matcher name=java.util.regex.Pattern.compile("([a-f0-9]{32}\\.(?:png|jpg|webp))\\.[a-f0-9]{32}\\.pending").matcher(staged.getFileName().toString());
                if(!name.matches()||!Files.isRegularFile(staged,LinkOption.NOFOLLOW_LINKS))continue;
                checked++;String assetName=name.group(1);
                boolean registered=!db.list("SELECT name FROM boost_media WHERE name=? FOR UPDATE",assetName).isEmpty();
                try{
                    if(registered||linked("/boost-media/"+assetName))restoreStaged(staged,root().resolve(assetName));
                    else deleteStaged(staged);
                }catch(IOException e){org.slf4j.LoggerFactory.getLogger(BoostMediaFiles.class).warn("Staged media recovery requires retry for {}",assetName);}
            }
        }catch(IOException e){org.slf4j.LoggerFactory.getLogger(BoostMediaFiles.class).warn("Media staging directory requires recovery");}
    }
}
