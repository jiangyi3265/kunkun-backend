"""Synthetic trust/roles exclusively in a marked, freshly created local QA database."""
import argparse,json,re,secrets,bcrypt
from pathlib import Path
from local_db import ROOT,configuration,mysql
p=argparse.ArgumentParser();p.add_argument('--fixture',required=True);p.add_argument('--seller',type=int);p.add_argument('--verify',action='store_true');a=p.parse_args()
f=Path(a.fixture).resolve();assert f.is_relative_to(ROOT/'.runtime');fixture=json.loads(f.read_text('utf-8'));db=fixture['database'];assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',db) and db!=configuration()['database'];assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
quote=lambda x:'CONVERT(0x'+str(x).encode().hex()+' USING utf8mb4)'
if a.verify:
    results={table:mysql('SELECT COUNT(*) AS records FROM '+table,db).strip() for table in ['boost_listing','boost_order','boost_site_content','boost_content_activity']}
    results['inventory']=mysql('SELECT id,pricing_mode,quantity,reserved_quantity,sold_quantity,status FROM boost_listing ORDER BY id',db)
    results['orders']=mysql('SELECT id,pricing_mode,unit_price_cents,quantity,original_cents,amount_cents,status FROM boost_order ORDER BY id',db)
    print(json.dumps(results,ensure_ascii=False))
elif a.seller:
    seller=a.seller;assert seller>0
    assert 'qa_mc_' in mysql('SELECT username FROM boost_member WHERE id='+str(seller),db)
    mysql("INSERT INTO boost_player_profile(member_id,shop_name,shop_status,realname_status,realname_provider,identity_subject_hash) VALUES ("+str(seller)+",'隔离按件测试店','APPROVED','VERIFIED','TENCENT_FACEID','"+'a'*64+"'); INSERT INTO boost_identity_session(id,member_id,provider,provider_token,status,expires_at,verified_at) VALUES ('qa_mc_"+str(seller)+"',"+str(seller)+",'TENCENT_FACEID','qa_fixture_"+str(seller)+"','VERIFIED','2030-01-01',NOW());",db)
    mysql("INSERT INTO boost_member_phone(member_id,phone) VALUES ("+str(seller)+",'139"+str(seller).zfill(8)+"')",db)
    print('Synthetic identity fixture installed only in '+db+'; NOT real identity verification')
else:
    for name in ['boost-menu.sql','boost-player-menu.sql','boost-rewards-menu.sql','boost-benefits-menu.sql','boost-content-menu.sql']:mysql((ROOT/'RuoYi-Vue/sql'/name).read_text('utf-8'),db)
    actors=[]
    for label,grants in [('content_reader',['boost:content:read']),('content_editor',['boost:content:read','boost:content:manage']),('users_only',['boost:members:read'])]:
        username='qa_mc_'+secrets.token_hex(6);password=secrets.token_urlsafe(15);digest=bcrypt.hashpw(password.encode(),bcrypt.gensalt()).decode();role='qa_mc_'+secrets.token_hex(6)
        mysql('INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag) VALUES ('+quote(label)+','+quote(role)+",99,'0','0'); INSERT INTO sys_user(user_name,nick_name,password,status,del_flag,pwd_update_date) VALUES ("+quote(username)+','+quote(label)+','+quote(digest)+",'0','0',NOW());",db)
        uid=int(mysql('SELECT user_id FROM sys_user WHERE user_name='+quote(username),db).strip().splitlines()[-1]);rid=int(mysql('SELECT role_id FROM sys_role WHERE role_key='+quote(role),db).strip().splitlines()[-1]);assert uid!=1 and rid!=1
        mysql(f'INSERT INTO sys_user_role(user_id,role_id) VALUES ({uid},{rid}); INSERT INTO sys_role_menu(role_id,menu_id) VALUES ({rid},87000)',db)
        for permission in grants:mysql(f'INSERT INTO sys_role_menu(role_id,menu_id) SELECT {rid},menu_id FROM sys_menu WHERE perms='+quote(permission),db)
        actors.append({'name':label,'credentials':{'username':username,'password':password},'userId':uid,'roleId':rid})
    (f.parent/'content-operators.json').write_text(json.dumps(actors),encoding='utf-8');print('Owned content menus and three permission-isolated operators created')
