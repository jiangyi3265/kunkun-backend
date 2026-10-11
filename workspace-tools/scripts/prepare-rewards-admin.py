"""Menus and operators exclusively inside a marked owned localhost fixture."""
import argparse,json,re,secrets,bcrypt
from pathlib import Path
from local_db import ROOT,configuration,mysql
p=argparse.ArgumentParser();p.add_argument('--fixture',required=True);p.add_argument('--revoke',action='store_true');a=p.parse_args()
f=Path(a.fixture).resolve();assert f.is_relative_to(ROOT/'.runtime');fixture=json.loads(f.read_text(encoding='utf-8'));db=fixture['database'];assert re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',db) and db!=configuration()['database'];assert fixture['marker'] in mysql('SELECT marker FROM qa_ui_fixture_marker',db)
private=f.parent/'rewards-operators.json';quote=lambda x:'CONVERT(0x'+str(x).encode().hex()+' USING utf8mb4)'
if a.revoke:
 actor=json.loads(private.read_text(encoding='utf-8'))[0];assert actor['userId']!=1
 mysql('DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id='+str(actor['roleId'])+" AND m.perms='boost:rewards:manage'",db)
 print('Owned rewards operator manage permission revoked')
else:
 assert not private.exists()
 assert mysql('SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (87009,87027,87028,87029)',db).strip().splitlines()[-1]=='0'
 mysql((ROOT/'RuoYi-Vue/sql/boost-player-menu.sql').read_text(encoding='utf-8'),db)
 mysql((ROOT/'RuoYi-Vue/sql/boost-rewards-menu.sql').read_text(encoding='utf-8'),db)
 actors=[]
 for label,permissions in [('catalog',['boost:rewards:read','boost:rewards:manage']),('fulfiller',['boost:rewards:read','boost:rewards:fulfill'])]:
  username='qa_reward_'+secrets.token_hex(5);password=secrets.token_urlsafe(15);digest=bcrypt.hashpw(password.encode(),bcrypt.gensalt()).decode();role='qa_rewards_'+secrets.token_hex(7)
  mysql('INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_by,create_time) VALUES ('+quote(label)+','+quote(role)+",99,'0','0','qa-owned',NOW()); INSERT INTO sys_user(user_name,nick_name,password,status,del_flag,create_by,create_time,pwd_update_date) VALUES ("+quote(username)+','+quote(label)+','+quote(digest)+",'0','0','qa-owned',NOW(),NOW());",db)
  uid=int(mysql('SELECT user_id FROM sys_user WHERE user_name='+quote(username),db).strip().splitlines()[-1]);rid=int(mysql('SELECT role_id FROM sys_role WHERE role_key='+quote(role),db).strip().splitlines()[-1]);assert uid!=1 and rid!=1
  mysql('INSERT INTO sys_user_role(user_id,role_id) VALUES ('+str(uid)+','+str(rid)+');',db)
  for perm in permissions:
   menus=mysql('SELECT menu_id FROM sys_menu WHERE perms='+quote(perm)+" AND status='0'",db).strip().splitlines()[1:];assert len(menus)==1
   mysql('INSERT INTO sys_role_menu(role_id,menu_id) VALUES ('+str(rid)+','+menus[0]+');',db)
  actors.append({'name':label,'credentials':{'username':username,'password':password},'userId':uid,'roleId':rid})
 private.write_text(json.dumps(actors),encoding='utf-8');print('Owned rewards menus and restricted operators prepared')
