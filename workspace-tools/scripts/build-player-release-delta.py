"""Exact immutable candidate delta, including byte-exact ZIP-member reuse for Spring JAR."""
import io,json,hashlib,zipfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
metadata=json.loads(Path(sys.argv[1]).read_text('utf-8'));old=json.loads(Path(sys.argv[2]).read_text('utf-8')) if len(sys.argv)>2 else json.loads((ROOT/'test-results/interconnect-20261008/server-before.json').read_text('utf-8'))
basis=Path(old['current']).name;old_archive=ROOT/'.runtime/releases'/basis/'release.zip'
digest=lambda b:hashlib.sha256(b).hexdigest()
target=Path(metadata['archive']);assert digest(target.read_bytes())==metadata['archiveSha256']
output=target.parent/'delta.zip';assert not output.exists()
manifest={'version':metadata['version'],'baseVersion':basis,'baseJarSha256':old['jarSha256'],'files':{},'changed':[]}
def segments(data):
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  ordered=sorted(z.infolist(),key=lambda f:f.header_offset);parts={}
  for i,f in enumerate(ordered):
   end=ordered[i+1].header_offset if i+1<len(ordered) else z.start_dir
   parts[f.filename]=(f.header_offset,end-f.header_offset)
  return parts,z.start_dir,ordered[0].header_offset
with zipfile.ZipFile(old_archive) as prev,zipfile.ZipFile(target) as current,zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as delta:
 oldjar=prev.read('ruoyi-admin.jar');assert digest(oldjar)==old['jarSha256']
 for name in current.namelist():
  data=current.read(name);manifest['files'][name]=digest(data)
  if name=='ruoyi-admin.jar':
   oldparts,_,_=segments(oldjar);parts,central,first=segments(data);instructions=[];rebuilt=bytearray()
   pieces=[('__prefix__',0,first)]+[(n,a,b) for n,(a,b) in parts.items()]+[('__central__',central,len(data)-central)]
   for index,(n,a,size) in enumerate(pieces):
    piece=data[a:a+size];prior=oldparts.get(n)
    if prior and oldjar[prior[0]:prior[0]+prior[1]]==piece:
     instructions.append({'offset':prior[0],'length':prior[1]});rebuilt.extend(oldjar[prior[0]:prior[0]+prior[1]])
    else:
     p='jar-patch/'+str(index);delta.writestr(p,piece);instructions.append({'file':p});rebuilt.extend(piece)
   assert bytes(rebuilt)==data;manifest['jarPieces']=instructions;manifest['jarBytes']=len(data)
  elif name not in prev.namelist() or digest(prev.read(name))!=digest(data):delta.writestr('files/'+name,data);manifest['changed'].append(name)
 delta.writestr('delta-manifest.json',json.dumps(manifest))
metadata.update(deltaArchive=str(output),deltaSha256=digest(output.read_bytes()),deltaBytes=output.stat().st_size)
Path(sys.argv[1]).write_text(json.dumps(metadata,ensure_ascii=False,indent=2),'utf-8')
print(json.dumps({'version':metadata['version'],'fullBytes':target.stat().st_size,'deltaBytes':metadata['deltaBytes'],'changedFiles':len(manifest['changed']),'byteExactJarVerified':True}))
