"""Create a fresh owned localhost DB; never reset or copy business data."""
import json,re,time,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1];sys.path.insert(0,str(root/'scripts'))
from local_db import mysql
private=json.loads((root/'.runtime/local-dev.json').read_text(encoding='utf-8'))
database='boost_verify_'+str(time.time_ns()//1000000)
assert re.fullmatch('boost_verify_[0-9]+',database)
mysql('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
environment={'BOOST_TEST_DB_URL':re.sub(r'(jdbc:mysql://[^/]+/)[^?]+',r'\g<1>'+database,private['BOOST_DB_URL']),'BOOST_TEST_DB_USER':private['BOOST_DB_USER'],'BOOST_TEST_DB_PASSWORD':private['BOOST_DB_PASSWORD']}
player_database='boost_verify_'+str(time.time_ns()//1000000+1)
migration_database='boost_verify_'+str(time.time_ns()//1000000+2)
rewards_database='boost_verify_'+str(time.time_ns()//1000000+3)
assert len({database,player_database,migration_database,rewards_database})==4
for prefix,name in [('BOOST_PLAYER_TEST_DB_',player_database),('BOOST_MIGRATION_TEST_DB_',migration_database),('BOOST_REWARDS_TEST_DB_',rewards_database)]:
    assert re.fullmatch('boost_verify_[0-9]+',name)
    mysql('CREATE DATABASE `'+name+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    environment[prefix+'URL']=re.sub(r'(jdbc:mysql://[^/]+/)[^?]+',r'\g<1>'+name,private['BOOST_DB_URL'])
    environment[prefix+'USER']=private['BOOST_DB_USER']
    environment[prefix+'PASSWORD']=private['BOOST_DB_PASSWORD']
assert re.fullmatch(r'jdbc:mysql://(localhost|127\.0\.0\.1)(:[0-9]+)?/boost_verify_[0-9]+\?.*',environment['BOOST_TEST_DB_URL'])
folder=root/'.runtime/player-acceptance-20261007';folder.mkdir(parents=True,exist_ok=True)
(folder/'test-db-environment.json').write_text(json.dumps(environment),encoding='utf-8')
out=Path(__import__('os').environ.get('PLAYER_ACCEPTANCE_OUT',root/'test-results/player-trade-20261007'));out.mkdir(parents=True,exist_ok=True)
(out/'isolated-mysql-tests.json').write_text(json.dumps({'database':database,'playerDatabase':player_database,'migrationDatabase':migration_database,'rewardsDatabase':rewards_database,'createdAt':time.time(),'emptyBeforeTests':True,'scope':'Four separate owned localhost test databases: existing races, player flows, V002 upgrade, rewards concurrency; no copied business data or real integrations'},indent=2),encoding='utf-8')
print('Fresh isolated MySQL test database created')
