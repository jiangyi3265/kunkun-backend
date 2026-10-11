"""Rebuild and compare copied local foreign keys inside a fresh owned fixture."""
import hashlib,json,re
from local_db import mysql

def foreign_keys(schema):
    query="SELECT JSON_OBJECT('name',k.CONSTRAINT_NAME,'table',k.TABLE_NAME,'column',k.COLUMN_NAME,'position',k.ORDINAL_POSITION,'referencedSchema',k.REFERENCED_TABLE_SCHEMA,'referencedTable',k.REFERENCED_TABLE_NAME,'referencedColumn',k.REFERENCED_COLUMN_NAME,'update',r.UPDATE_RULE,'delete',r.DELETE_RULE) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND r.TABLE_NAME=k.TABLE_NAME WHERE k.TABLE_SCHEMA='"+schema+"' AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION"
    # Encode each metadata row so CLI line/control-character rendering cannot split JSON.
    query=query.replace("SELECT JSON_OBJECT(","SELECT HEX(CAST(JSON_OBJECT(",1).replace(") FROM information_schema", ") AS CHAR)) AS fk_hex FROM information_schema",1)
    return [json.loads(bytes.fromhex(row).decode('utf-8')) for row in mysql(query,schema).splitlines()[1:] if row.strip()]

def clone_foreign_keys(source,database,tables):
    assert re.fullmatch(r'boost_verify_[A-Za-z0-9_]+',database) and source!=database
    assert not foreign_keys(database),'Fresh copied structures must not already have foreign keys'
    source_keys=[row for row in foreign_keys(source) if row['table'] in tables]
    groups={}
    for row in source_keys:
        assert row['referencedSchema']==source and row['referencedTable'] in tables,'Cross-schema references cannot belong to an isolated fixture'
        assert all(re.fullmatch(r'[A-Za-z0-9_]+',row[key]) for key in ('name','table','column','referencedTable','referencedColumn'))
        assert row['update'] in ('CASCADE','SET NULL','RESTRICT','NO ACTION') and row['delete'] in ('CASCADE','SET NULL','RESTRICT','NO ACTION')
        groups.setdefault((row['table'],row['name']),[]).append(row)
    for (table,name),keys in groups.items():
        columns=','.join('`'+row['column']+'`' for row in keys);referenced=','.join('`'+row['referencedColumn']+'`' for row in keys)
        mysql('ALTER TABLE `'+database+'`.`'+table+'` ADD CONSTRAINT `'+name+'` FOREIGN KEY ('+columns+') REFERENCES `'+database+'`.`'+keys[0]['referencedTable']+'` ('+referenced+') ON UPDATE '+keys[0]['update']+' ON DELETE '+keys[0]['delete'],database)
    expected=[{**row,'referencedSchema':database} for row in source_keys]
    assert foreign_keys(database)==expected,'Fixture foreign key definitions differ from the source'
    return {'foreignKeys':len(groups),'foreignKeyDefinitionsVerified':True,'foreignKeyDefinitionsSha256':hashlib.sha256(json.dumps(source_keys,sort_keys=True).encode()).hexdigest()}
