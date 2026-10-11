"""Dump and restore only the owned localhost player test DB; never touch production/shared data."""
import hashlib,json,os,re,subprocess,time
from pathlib import Path
from local_db import ROOT,configuration,mysql

out=Path(os.environ.get('PLAYER_ACCEPTANCE_OUT',ROOT/'test-results/player-trade-20261007/final'))
metadata=json.loads((out/'isolated-mysql-tests.json').read_text(encoding='utf-8'))
source=metadata['playerDatabase'];target='boost_verify_restore_'+str(time.time_ns()//1000000)
assert re.fullmatch(r'boost_verify_[0-9]+',source) and re.fullmatch(r'boost_verify_restore_[0-9]+',target)
private=json.loads((ROOT/'.runtime/player-acceptance-20261007/test-db-environment.json').read_text(encoding='utf-8'))
assert re.fullmatch(r'jdbc:mysql://(localhost|127\.0\.0\.1)(:[0-9]+)?/'+source+r'\?.*',private['BOOST_PLAYER_TEST_DB_URL'])
c=configuration();assert c['host'] in ('localhost','127.0.0.1') and source!=c['database'] and target!=c['database']
folder=ROOT/'.runtime'/('player-backup-'+str(time.time_ns()//1000000));folder.mkdir()
dumpfile=folder/'isolated-player.sql';env=os.environ.copy();env['MYSQL_PWD']=c['password']
started=time.time();command=['mysqldump','--host='+c['host'],'--port='+c['port'],'--user='+c['user'],'--default-character-set=utf8mb4','--single-transaction','--no-tablespaces','--set-gtid-purged=OFF','--skip-comments','--skip-dump-date',source]
with dumpfile.open('wb') as handle:
    result=subprocess.run(command,stdout=handle,stderr=subprocess.PIPE,env=env)
assert result.returncode==0,'Owned database dump failed'
mysql('CREATE DATABASE `'+target+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
mysql(dumpfile.read_text(encoding='utf-8'),target)
def fingerprint(database):
    names=mysql('SHOW TABLES',database).strip().splitlines()[1:]
    result={}
    for name in names:
        assert re.fullmatch(r'boost_[a-z0-9_]+',name)
        columns=mysql("SELECT column_name FROM information_schema.columns WHERE table_schema='"+database+"' AND table_name='"+name+"' ORDER BY ordinal_position",database).strip().splitlines()[1:]
        assert columns and all(re.fullmatch(r'[a-z0-9_]+',column) for column in columns)
        # HEX preserves embedded newlines, tabs and NULL/empty distinctions in one physical row.
        expression=','.join("IF(`"+column+"` IS NULL,'N',CONCAT('V',HEX(CAST(`"+column+"` AS BINARY))))" for column in columns)
        values=mysql("SELECT SHA2(CONCAT_WS('|',"+expression+"),256) AS row_hash FROM `"+name+"`",database).splitlines()[1:]
        result[name]={'rows':len(values),'sha256':hashlib.sha256('\n'.join([','.join(columns),*sorted(values)]).encode()).hexdigest(),'encoding':'column-ordered HEX with explicit NULL marker; sorted row SHA-256'}
    return result
before=fingerprint(source);after=fingerprint(target);assert before==after,'Restored row fingerprints differ'
assert before['boost_order']['rows']>=1 and before['boost_player_profile']['rows']>=1,'Recovery exercise must include new item data'
foreign=lambda database:mysql("SELECT table_name,column_name,referenced_table_name,referenced_column_name FROM information_schema.key_column_usage WHERE table_schema='"+database+"' AND referenced_table_name IS NOT NULL ORDER BY table_name,column_name",database)
assert foreign(source)==foreign(target),'Restored foreign keys differ'
report={'status':'已实测通过','startedAtEpoch':started,'finishedAtEpoch':time.time(),'sourceDatabase':source,'restoredDatabase':target,'dumpSha256':hashlib.sha256(dumpfile.read_bytes()).hexdigest(),'dumpBytes':dumpfile.stat().st_size,'rowFingerprints':before,'foreignKeysMatched':True,'scope':'Real local MySQL dump/restore of isolated new item fixture including reserved order, certified profiles with identity substitutes; no production backup/recovery and no real identity verification. Private SQL excluded from evidence package.','cleanup':'Owned dump and both isolated databases retained for inspection; no shared data modified.'}
(out/'mysql-backup-restore.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS isolated MySQL dump/restore: all '+str(len(before))+' table row fingerprints and foreign keys matched')
