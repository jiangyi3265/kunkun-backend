"""Create/inspect an expressly isolated media-permission fixture; never mutate shared business data."""
import argparse,hashlib,json,re,secrets,struct,sys,time,uuid,zlib
import bcrypt
from pathlib import Path
from local_db import ROOT,configuration,mysql
from isolated_foreign_keys import clone_foreign_keys

def quote(value):
    # UTF-8 hex literals avoid shell/SQL escaping and do not disclose data to stdout.
    return "(CONVERT(0x"+str(value).encode('utf-8').hex()+" USING utf8mb4) COLLATE utf8mb4_unicode_ci)"
def read(file):return json.loads(Path(file).read_text(encoding='utf-8-sig'))
def png():
    width,height=320,200
    rows=b''.join(b'\0'+bytes((32,104,176) if y<100 else (176,208,240))*width for y in range(height))
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')
def execute(sql,database):return mysql(sql,database=database)
def rows(sql,database):return [json.loads(line) for line in execute(sql,database).splitlines()[1:] if line.strip()]
def checked(file):
    path=Path(file).resolve();assert path.is_relative_to(ROOT/'.runtime'),'Fixture metadata must stay in private local runtime'
    f=read(path);assert re.fullmatch(r'boost_verify_media_[A-Za-z0-9_]{1,32}',f['database'])
    # Runtime callers may deliberately set BOOST_DB_URL to this same target.
    # Ownership is proved by the fresh prefix and marker, not by a different env default.
    assert f['database'].lower() not in ('ha','mysql','sys','test')
    marker=rows('SELECT JSON_OBJECT(\'marker\',marker) FROM qa_media_fixture_marker',f['database'])
    assert marker==[{'marker':f['marker']}],'Isolated database ownership marker mismatch'
    directory=Path(f['mediaDirectory']).resolve();assert directory.is_relative_to(ROOT/'.runtime')
    assert directory.name.startswith('media-permissions-')
    return path,f

parser=argparse.ArgumentParser();parser.add_argument('action',choices=('prepare','seed','inspect'));parser.add_argument('--fixture');parser.add_argument('--output');args=parser.parse_args()
if args.action=='prepare':
    assert args.output,'--output is required'
    target=Path(args.output).resolve();assert target.is_relative_to(ROOT/'.runtime');assert not target.exists()
    stamp=str(time.time_ns()//1000000);database='boost_verify_media_'+stamp;source=configuration()['database']
    assert re.fullmatch(r'[A-Za-z0-9_]+',source);assert database!=source
    marker='media_matrix_'+uuid.uuid4().hex
    # The target database did not exist. Only target-qualified CREATE/INSERT statements write.
    exists=rows("SELECT JSON_OBJECT('count',COUNT(*)) FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME=BINARY "+quote(database),source)[0]['count']
    assert exists==0
    execute('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',source)
    names=[line for line in execute('SHOW TABLES',source).splitlines()[1:] if re.fullmatch(r'(sys|boost)_[a-z0-9_]+',line)]
    assert 'sys_user' in names and 'boost_media' in names and 'boost_order' in names
    sql=['SET FOREIGN_KEY_CHECKS=0']
    for name in names:
        sql.append('CREATE TABLE `'+database+'`.`'+name+'` LIKE `'+source+'`.`'+name+'`')
        condition=' WHERE user_id=1' if name in ('sys_user','sys_user_role','sys_user_post') else ''
        copy=name in ('sys_user','sys_user_role','sys_user_post','sys_config','sys_dept','sys_dict_data','sys_dict_type','sys_menu','sys_post','sys_role','sys_role_dept','sys_role_menu','boost_config')
        if copy:sql.append('INSERT INTO `'+database+'`.`'+name+'` SELECT * FROM `'+source+'`.`'+name+'`'+condition)
    sql+=['SET FOREIGN_KEY_CHECKS=1','CREATE TABLE qa_media_fixture_marker(marker VARCHAR(80) PRIMARY KEY,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)','INSERT INTO qa_media_fixture_marker(marker) VALUES ('+quote(marker)+')']
    execute(';\n'.join(sql)+';',database)
    constraints=clone_foreign_keys(source,database,names)
    directory=ROOT/'.runtime'/('media-permissions-'+stamp);assert not directory.exists();directory.mkdir(parents=True)
    name=uuid.uuid4().hex+'.png';image=png();(directory/name).write_bytes(image);url='/boost-media/'+name
    probe_title='QA isolated media probe '+stamp
    execute("INSERT INTO boost_member(username,password_hash,nickname,certification,proof) VALUES ('qa_media_probe','not-a-login-password','媒体隔离探针','PENDING',"+quote(json.dumps([url]))+"); SET @member=LAST_INSERT_ID(); INSERT INTO boost_media(name,owner_id,purpose,size_bytes) VALUES ("+quote(name)+",@member,'CERTIFICATION',"+str(len(image))+"); INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,duration_hours,price_cents,description,images,status) VALUES (@member,'REQUEST',"+quote(probe_title)+",'QA','QA','QA',1,0,'隔离库身份探针，不是经营需求','[]','PENDING');",database)
    probe=rows("SELECT JSON_OBJECT('memberId',m.id,'listingId',l.id) FROM boost_member m JOIN boost_listing l ON l.owner_id=m.id WHERE m.username='qa_media_probe'",database)[0]
    # RuoYi's normal login contract accepts at most 20 characters.
    password=secrets.token_urlsafe(15);password_hash=bcrypt.hashpw(password.encode(),bcrypt.gensalt()).decode()
    execute('UPDATE sys_user SET password='+quote(password_hash)+" WHERE user_id=1;",database)
    admin=rows("SELECT JSON_OBJECT('username',user_name) FROM sys_user WHERE user_id=1 AND status='0' AND del_flag='0'",database);assert len(admin)==1
    f={'database':database,'marker':marker,**constraints,'mediaDirectory':str(directory),'credentials':{'username':admin[0]['username'],'password':password},'probe':{**probe,'title':probe_title,'path':url,'sha256':hashlib.sha256(image).hexdigest()},'createdAtUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'scope':'Only sys configuration/identity rows copied (one admin with new random password); boost structures/config, one ownership probe. No copied business members, payments, ledger, delivery or aftersales.'}
    target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(f,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'prepared':True,'database':database,'fixture':str(target),'mediaDirectory':str(directory),'probe':f['probe']},ensure_ascii=False))
elif args.action=='seed':
    file,f=checked(args.fixture);data=json.load(sys.stdin)
    assert 'business' not in f,'Fixture already seeded'
    ids=data['members'];assert len(set(ids.values()))==3 and all(type(i)==int and i>0 for i in ids.values())
    assets=data['assets'];assert set(assets)=={'CERTIFICATION','LISTING','DELIVERY','AFTERSALE','CHAT','AVATAR'}
    for purpose,url in assets.items():
        assert re.fullmatch(r'/boost-media/[a-f0-9]{32}\.png',url)
        found=rows("SELECT JSON_OBJECT('ownerId',owner_id,'purpose',purpose) FROM boost_media WHERE name="+quote(url.rsplit('/',1)[1]),f['database'])
        expected_owner=ids['peer'] if purpose=='AFTERSALE' else ids['owner']
        assert found==[{'ownerId':expected_owner,'purpose':purpose}],'Only real uploads owned by the matching fixture participant are valid'
        assert (Path(f['mediaDirectory'])/url.rsplit('/',1)[1]).is_file()
    own,peer=ids['owner'],ids['peer'];title='QA media fixture '+f['marker'][-12:];order_no='QAMEDIA'+f['marker'][-24:]
    j=lambda value:quote(json.dumps(value,ensure_ascii=False))
    execute("UPDATE boost_member SET certification='PENDING',skill='仅验证媒体授权',proof="+j([assets['CERTIFICATION']])+" WHERE id="+str(own)+"; INSERT INTO boost_listing(owner_id,kind,title,game,zone,platform,duration_hours,price_cents,description,images,status) VALUES ("+str(own)+",'SERVICE',"+quote(title)+",'QA','QA','QA',1,0,'私密媒体授权独立夹具，不是付款或业务交付',"+j([assets['LISTING']])+",'OFFLINE'); SET @listing=LAST_INSERT_ID(); INSERT INTO boost_order(order_no,request_key,buyer_id,worker_id,listing_id,title,game,zone,platform,duration_hours,original_cents,amount_cents,status,payment_mode,snapshot_images) VALUES ("+quote(order_no)+","+quote(f['marker'])+","+str(peer)+","+str(own)+",@listing,"+quote(title)+",'QA','QA','QA',1,0,0,'REVIEW','MEDIA_QA_FIXTURE',"+j([assets['LISTING']])+"); SET @order=LAST_INSERT_ID(); INSERT INTO boost_progress(order_id,member_id,percent,note,images) VALUES (@order,"+str(own)+",100,'直接种子仅用于读取媒体权限，未走付款/成果流程',"+j([assets['DELIVERY']])+"); INSERT INTO boost_aftersale(order_id,member_id,reason,description,images,amount_cents,previous_status,status) VALUES (@order,"+str(peer)+",'媒体隔离夹具','不提交退款或资金操作，仅验证已有凭证可见性',"+j([assets['AFTERSALE']])+",0,'REVIEW','PENDING'); INSERT INTO boost_conversation(member_a,member_b,listing_id) VALUES ("+str(min(own,peer))+","+str(max(own,peer))+",@listing); SET @conversation=LAST_INSERT_ID(); INSERT INTO boost_message(conversation_id,sender_id,content,kind) VALUES (@conversation,"+str(own)+","+quote(assets['CHAT'])+",'IMAGE');",f['database'])
    business=rows("SELECT JSON_OBJECT('listingId',o.listing_id,'orderId',o.id,'orderNo',o.order_no,'aftersaleId',a.id) FROM boost_order o JOIN boost_aftersale a ON a.order_id=o.id WHERE o.order_no="+quote(order_no),f['database'])[0]
    f.update({'business':business,'members':ids,'assets':assets});file.write_text(json.dumps(f,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'seeded':True,**business,'scope':'Explicit isolated SQL read fixture, zero amounts, no paid_at or channel IDs; does not prove delivery/aftersale/payment business transitions'},ensure_ascii=False))
else:
    _,f=checked(args.fixture)
    result={'database':f['database'],'markerValid':True,'walletTotalCents':rows("SELECT JSON_OBJECT('value',COALESCE(SUM(balance_cents),0)) FROM boost_member",f['database'])[0]['value']}
    for name in ('boost_ledger','boost_withdrawal','boost_session'):
        result[name]=rows("SELECT JSON_OBJECT('value',COUNT(*)) FROM "+name,f['database'])[0]['value']
    result['activeQaOperators']=rows("SELECT JSON_OBJECT('value',COUNT(*)) FROM sys_user WHERE user_name LIKE 'qa\\_mm\\_%' AND del_flag='0'",f['database'])[0]['value']
    result['activeQaRoles']=rows("SELECT JSON_OBJECT('value',COUNT(*)) FROM sys_role WHERE role_key LIKE 'qa\\_mm\\_%' AND del_flag='0'",f['database'])[0]['value']
    if f.get('members'):
        result['qaSessions']=rows("SELECT JSON_OBJECT('memberId',m.id,'sessions',(SELECT COUNT(*) FROM boost_session s WHERE s.member_id=m.id)) FROM boost_member m WHERE m.id IN ("+','.join(str(x) for x in f['members'].values())+") ORDER BY m.id",f['database'])
    print(json.dumps(result,ensure_ascii=False))
