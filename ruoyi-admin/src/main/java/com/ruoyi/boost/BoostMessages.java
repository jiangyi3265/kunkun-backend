package com.ruoyi.boost;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.boost.BoostDb.*;
import static com.ruoyi.boost.BoostInput.*;

@Service
public class BoostMessages {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private BoostPlayerMarket player;
    private final BoostDb db;
    public BoostMessages(BoostDb db){this.db=db;}
    @Transactional
    public long open(long member,Map<String,Object> b) {
        long peer=number(b,"peerId",1,Long.MAX_VALUE);BoostException.require(peer!=member,"不能与自己聊天");
        long a=Math.min(member,peer),c=Math.max(member,peer);
        Map<String,Object> first=db.one("SELECT id,status FROM boost_member WHERE id=? FOR UPDATE",a),second=db.one("SELECT id,status FROM boost_member WHERE id=? FOR UPDATE",c);
        db.activeMember(member);
        BoostException.require("ACTIVE".equals(str(peer==a?first:second,"status")),"对方账号已停用，当前不可聊天");
        List<Map<String,Object>> rows=db.list("SELECT id FROM boost_conversation WHERE member_a=? AND member_b=?",a,c);
        if(!rows.isEmpty())return num(rows.get(0),"id");
        Long listing=b.get("listingId")==null?null:number(b,"listingId",1,Long.MAX_VALUE);
        // A hidden listing must not be exposed through a conversation preview.
        // Keep order participants able to chat even after the service goes offline.
        if(listing!=null&&visibleListing(member,listing)==null)listing=null;
        return db.insert("INSERT INTO boost_conversation(member_a,member_b,listing_id) VALUES (?,?,?)",a,c,listing);
    }
    private Map<String,Object> visibleListing(long member,long id){
        boolean items=player!=null&&player.enabled();
        List<Map<String,Object>> rows=db.list("SELECT l.id,l.title,l.game,l.price_cents,l.status"+(items?",l.business_type,l.item_category,l.quantity,l.images":"")+" FROM boost_listing l JOIN boost_member m ON m.id=l.owner_id WHERE l.id=? AND "+BoostMarket.VISIBLE_TO_MEMBER+(items?BoostMarket.PLAYER_VISIBLE_FILTER:""),id,member);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Map<String,Object>> conversations(long member) {
        return db.list("SELECT c.*,m.nickname,m.avatar,(SELECT content FROM boost_message WHERE conversation_id=c.id ORDER BY id DESC LIMIT 1) AS last_message,(SELECT COUNT(*) FROM boost_message WHERE conversation_id=c.id AND sender_id<>? AND is_read=FALSE) AS unread FROM boost_conversation c JOIN boost_member m ON m.id=CASE WHEN c.member_a=? THEN c.member_b ELSE c.member_a END WHERE c.member_a=? OR c.member_b=? ORDER BY c.updated_at DESC",member,member,member,member);
    }
    public Map<String,Object> conversations(long member,Map<String,String> q){
        String keyword=q.getOrDefault("keyword","").trim();BoostException.require(keyword.length()<=100,"搜索内容过长");
        String from="FROM boost_conversation c JOIN boost_member m ON m.id=CASE WHEN c.member_a=? THEN c.member_b ELSE c.member_a END WHERE (c.member_a=? OR c.member_b=?) AND (?='' OR LOCATE(?,m.nickname)>0 OR EXISTS (SELECT 1 FROM boost_message sm WHERE sm.conversation_id=c.id AND sm.kind='TEXT' AND LOCATE(?,sm.content)>0))";
        // Peer search and history search share the same participant constraint.
        Map<String,Object> result=BoostPages.query(db,q,"SELECT c.*,m.nickname,m.avatar,(SELECT content FROM boost_message WHERE conversation_id=c.id ORDER BY id DESC LIMIT 1) AS last_message",from,"c.updated_at DESC,c.id DESC",member,member,member,keyword,keyword,keyword);
        for(Map<String,Object> row:(List<Map<String,Object>>)result.get("items"))row.put("unread",db.count("SELECT COUNT(*) FROM boost_message WHERE conversation_id=? AND sender_id<>? AND is_read=FALSE",num(row,"id"),member));
        return result;
    }
    public Map<String,Object> notifications(long member,Map<String,String> q){Map<String,Object> result=BoostPages.query(db,q,"SELECT *","FROM boost_notification WHERE member_id=?","id DESC",member);result.put("unread",db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=? AND is_read=FALSE",member));return result;}
    public Map<String,Object> summary(long member){return map("unread",db.count("SELECT COUNT(*) FROM boost_notification WHERE member_id=? AND is_read=FALSE",member)+db.count("SELECT COUNT(*) FROM boost_message m JOIN boost_conversation c ON c.id=m.conversation_id WHERE (c.member_a=? OR c.member_b=?) AND m.sender_id<>? AND m.is_read=FALSE",member,member,member),"noticeId",db.count("SELECT COALESCE(MAX(id),0) FROM boost_notification WHERE member_id=?",member),"messageId",db.count("SELECT COALESCE(MAX(m.id),0) FROM boost_message m JOIN boost_conversation c ON c.id=m.conversation_id WHERE (c.member_a=? OR c.member_b=?) AND m.sender_id<>?",member,member,member));}
    private Map<String,Object> conversation(long member,long id) {
        return conversation(member,id,false);
    }
    private Map<String,Object> conversation(long member,long id,boolean lock) {
        Map<String,Object> c=db.one("SELECT * FROM boost_conversation WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(num(c,"memberA")!=member&&num(c,"memberB")!=member)throw new BoostException(403,"无权访问此会话");return c;
    }
    public Map<String,Object> messages(long member,long id,long before) {
        return messages(member,id,before,0);
    }
    public Map<String,Object> messages(long member,long id,long before,long after) {
        Map<String,Object> c=conversation(member,id);long peer=num(c,"memberA")==member?num(c,"memberB"):num(c,"memberA");
        c.put("peer",db.one("SELECT id,nickname,avatar FROM boost_member WHERE id=?",peer));
        if(player!=null)((Map<String,Object>)c.get("peer")).put("shopStatus",player.profile(peer).get("shopStatus"));
        if(c.get("listingId")!=null)c.put("listing",visibleListing(member,num(c,"listingId")));
        List<Map<String,Object>> rows=after>0
            ?db.list("SELECT * FROM boost_message WHERE conversation_id=? AND id>? ORDER BY id ASC LIMIT 50",id,after)
            :db.list("SELECT * FROM boost_message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT 50",id,before>0?before:Long.MAX_VALUE);
        if(after==0)Collections.reverse(rows);
        c.put("items",rows);c.put("hasMore",after==0&&rows.size()==50);c.put("hasNewer",after>0&&rows.size()==50);
        c.put("readThrough",db.count("SELECT COALESCE(MAX(id),0) FROM boost_message WHERE conversation_id=? AND sender_id=? AND is_read=TRUE",id,member));
        return c;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void send(long member,long id,Map<String,Object> b) {
        // Acquire the member lock before any consistent read establishes a MySQL REPEATABLE READ snapshot.
        db.activeMember(member);
        conversation(member,id,true);String kind=choice(b,"kind","TEXT","IMAGE"),content=text(b,"content",1,1500),requestKey=text(b,"requestKey",8,64);
        Map<String,Object> payload=new LinkedHashMap<>(b);payload.put("conversationId",id);String hash=BoostIdempotency.digest(payload);
        // The sender member lock serializes this sender's keys. A missing key must not
        // acquire a unique-index gap lock shared by other senders/conversations.
        List<Map<String,Object>> repeated=db.list("SELECT request_hash FROM boost_message WHERE sender_id=? AND request_key=?",member,requestKey);
        if(!repeated.isEmpty()){BoostIdempotency.same(repeated.get(0),hash);return;}
        if("IMAGE".equals(kind))db.uploadedImage(content,member,"CHAT");
        db.run("INSERT INTO boost_message(conversation_id,sender_id,content,kind,request_key,request_hash) VALUES (?,?,?,?,?,?)",id,member,content,kind,requestKey,hash);
        db.run("UPDATE boost_conversation SET updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
    }
    @Transactional
    public void read(long member,long id){read(member,id,Long.MAX_VALUE);}
    @Transactional
    public void read(long member,long id,long through){db.activeMember(member);conversation(member,id);BoostException.require(through>=0,"消息编号无效");db.run("UPDATE boost_message SET is_read=TRUE WHERE conversation_id=? AND sender_id<>? AND id<=?",id,member,through);}
    @Transactional
    public void readNotice(long member,long id){db.activeMember(member);db.one("SELECT id FROM boost_notification WHERE id=? AND member_id=?",id,member);db.run("UPDATE boost_notification SET is_read=TRUE WHERE id=? AND member_id=?",id,member);}
    public List<Map<String,Object>> notifications(long member){return db.list("SELECT * FROM boost_notification WHERE member_id=? ORDER BY id DESC LIMIT 100",member);}
    @Transactional
    public void readAll(long member){db.activeMember(member);db.run("UPDATE boost_notification SET is_read=TRUE WHERE member_id=?",member);db.run("UPDATE boost_message SET is_read=TRUE WHERE sender_id<>? AND conversation_id IN (SELECT id FROM boost_conversation WHERE member_a=? OR member_b=?)",member,member,member);}
}
