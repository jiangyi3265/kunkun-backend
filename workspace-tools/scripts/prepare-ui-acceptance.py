"""Fresh owned UI database/files. Shared local MySQL is read only, never reset."""
import argparse,json,re,secrets,time,uuid
from pathlib import Path
import bcrypt
from local_db import ROOT,configuration,mysql

parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
destination=Path(args.output).resolve()
assert destination.is_relative_to(ROOT/'.runtime') and not destination.exists()
private=json.loads((ROOT/'.runtime/local-dev.json').read_text(encoding='utf-8'))
source=configuration()['database'];assert re.fullmatch(r'[A-Za-z0-9_]+',source)
database='boost_verify_ui_'+str(time.time_ns()//1000000)+'_'+uuid.uuid4().hex[:6]
assert database!=source and re.fullmatch(r'boost_verify_ui_[0-9]+_[a-f0-9]{6}',database)
names=mysql('SHOW TABLES',source).strip().splitlines()[1:]
names=[n for n in names if re.fullmatch(r'(sys|boost)_[a-z0-9_]+',n)]
assert {'sys_user','boost_member','boost_media','boost_order'}.issubset(names)
mysql('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',source)
copy={'sys_user','sys_user_role','sys_user_post','sys_config','sys_dept','sys_dict_data','sys_dict_type','sys_menu','sys_post','sys_role','sys_role_dept','sys_role_menu','boost_config'}
statements=['SET FOREIGN_KEY_CHECKS=0']
for table in names:
    statements.append('CREATE TABLE `'+database+'`.`'+table+'` LIKE `'+source+'`.`'+table+'`')
    if table in copy:
        condition=' WHERE user_id=1' if table in ('sys_user','sys_user_role','sys_user_post') else ''
        statements.append('INSERT INTO `'+database+'`.`'+table+'` SELECT * FROM `'+source+'`.`'+table+'`'+condition)
statements.append('SET FOREIGN_KEY_CHECKS=1')
mysql(';\n'.join(statements)+';',database)
from isolated_foreign_keys import clone_foreign_keys
constraints=clone_foreign_keys(source,database,names)
empty={table:int(mysql('SELECT COUNT(*) FROM `'+table+'`',database).strip().splitlines()[-1]) for table in names if table.startswith('boost_') and table!='boost_config'}
assert all(value==0 for value in empty.values()),'Business data must be empty before isolated demo initialization'
password=secrets.token_urlsafe(15)
digest=bcrypt.hashpw(password.encode(),bcrypt.gensalt()).decode()
quote=lambda value:'CONVERT(0x'+str(value).encode().hex()+' USING utf8mb4)'
mysql('UPDATE sys_user SET password='+quote(digest)+' WHERE user_id=1',database)
username=mysql("SELECT user_name FROM sys_user WHERE user_id=1 AND status='0' AND del_flag='0'",database).strip().splitlines()
assert len(username)==2
marker='ui_acceptance_'+uuid.uuid4().hex
mysql('CREATE TABLE qa_ui_fixture_marker(marker VARCHAR(80) PRIMARY KEY); INSERT INTO qa_ui_fixture_marker VALUES ('+quote(marker)+');',database)
destination.parent.mkdir(parents=True,exist_ok=True)
media=destination.parent/'media';assert not media.exists();media.mkdir()
environment={**private,'BOOST_DB_URL':re.sub(r'(jdbc:mysql://[^/]+/)[^?]+',r'\g<1>'+database,private['BOOST_DB_URL'])}
assert '/'+database+'?' in environment['BOOST_DB_URL']
config=destination.parent/'environment.json';assert not config.exists();config.write_text(json.dumps(environment),encoding='utf-8')
fixture={'database':database,'marker':marker,'sourceReadonly':source,'mediaDirectory':str(media),'environmentFile':str(config),'credentials':{'username':username[1],'password':password},'emptyBusinessTablesBeforeInitialization':empty,**constraints,'scope':'Fresh isolated UI fixture; only configuration and one admin copied read only. Business tables are empty before local demo setup. The separately recorded SQL wallet history is prepared only after actual bootstrap and before funds snapshots. All source foreign keys rebuilt into the target and compared. No shared business data/files. Shared local Redis reused without reset, only owned login/captcha keys; no real payment or marketing integration.'}
destination.write_text(json.dumps(fixture,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in fixture.items() if k!='credentials'},ensure_ascii=False))
