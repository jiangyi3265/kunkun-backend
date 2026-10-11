package com.ruoyi.boost;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import static com.ruoyi.boost.BoostDb.*;

/** Operator uploads are public marketing images, never member certification material. */
@Service public class BoostRewardsMedia {
 private final BoostDb db;
 @Value("${boost.media-dir:./.runtime/boost-media}") private String directory;
 public BoostRewardsMedia(BoostDb db){this.db=db;}
 private Path root(){return Paths.get(directory).toAbsolutePath().normalize().resolve("rewards");}
 public String validate(String url){if(url.isEmpty())return url;BoostException.require(url.matches("/boost-reward-media/[a-f0-9]{32}\\.png"),"请使用后台上传的公开活动图片");String name=url.substring(20);BoostException.require(db.count("SELECT COUNT(*) FROM boost_reward_media WHERE name=?",name)==1&&Files.isRegularFile(root().resolve(name)),"活动图片不存在，请重新上传");return url;}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Object upload(long admin,MultipartFile file)throws IOException {
  db.one("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' FOR UPDATE",admin);
  BoostException.require(file.getSize()>0&&file.getSize()<=5*1024*1024,"图片需在 5MB 内");
  if(db.count("SELECT COUNT(*) FROM boost_reward_media WHERE admin_id=? AND created_at>?",admin,java.sql.Timestamp.from(Instant.now().minusSeconds(3600)))>=60)throw new BoostException(429,"上传过于频繁");
  long used=db.count("SELECT COALESCE(SUM(size_bytes),0) FROM boost_reward_media WHERE admin_id=?",admin);BoostException.require(used<100*1024*1024,"公开活动图片空间已满");
  java.awt.image.BufferedImage picture;
  try(javax.imageio.stream.ImageInputStream stream=ImageIO.createImageInputStream(file.getInputStream())){Iterator<javax.imageio.ImageReader>rs=ImageIO.getImageReaders(stream);BoostException.require(rs.hasNext(),"请选择 PNG/JPG 图片");javax.imageio.ImageReader r=rs.next();try{r.setInput(stream);BoostException.require(List.of("png","jpeg","jpg").contains(r.getFormatName().toLowerCase(Locale.ROOT)),"请选择 PNG/JPG 图片");BoostException.require((long)r.getWidth(0)*r.getHeight(0)<=16000000,"图片尺寸过大");picture=r.read(0);}finally{r.dispose();}}catch(IOException e){throw new BoostException(400,"图片无法读取");}
  BoostException.require(picture!=null,"图片无法读取");ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(picture,"png",bytes);BoostException.require(bytes.size()<=10*1024*1024&&used+bytes.size()<=100*1024*1024,"图片转换后过大");String name=UUID.randomUUID().toString().replace("-","")+".png";Files.createDirectories(root());Path target=root().resolve(name);Files.write(target,bytes.toByteArray(),StandardOpenOption.CREATE_NEW);
  org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){}}});db.run("INSERT INTO boost_reward_media(name,admin_id,size_bytes) VALUES (?,?,?)",name,admin,bytes.size());return map("url","/boost-reward-media/"+name);
 }
 public ResponseEntity<Resource> read(String name){BoostException.require(name.matches("[a-f0-9]{32}\\.png"),"图片地址无效");Path file=root().resolve(name);if(db.count("SELECT COUNT(*) FROM boost_reward_media WHERE name=?",name)!=1||!Files.isRegularFile(file))throw new BoostException(404,"图片不存在");return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.maxAge(60,java.util.concurrent.TimeUnit.SECONDS)).body(new FileSystemResource(file));}
}
