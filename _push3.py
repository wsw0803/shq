import base64, json, subprocess, io, sys
T='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'
BASE='https://api.github.com/repos/wsw0803/shq/contents/'
files=sys.argv[1:]
for loc in files:
    api=loc
    r=subprocess.run(['curl','-s','--max-time','9','-H','Authorization: token '+T,'-H','Accept: application/vnd.github+json',BASE+api],capture_output=True,text=True)
    sha=''
    try:
        d=json.loads(r.stdout)
        if isinstance(d,dict): sha=d.get('sha','') or ''
    except Exception: sha=''
    c=io.open(loc,encoding='utf-8').read()
    body={'message':'feat: 日志卡片新增 GitHub 推送按钮与 Token 脱敏显示','content':base64.b64encode(c.encode('utf-8')).decode('ascii'),'branch':'main'}
    if sha: body['sha']=sha
    io.open('_pb.json','w',encoding='utf-8').write(json.dumps(body))
    r2=subprocess.run(['curl','-s','--max-time','12','-o','_pr.json','-w','%{http_code}','-X','PUT','-H','Authorization: token '+T,'-H','Accept: application/vnd.github+json','-H','Content-Type: application/json','--data-binary','@_pb.json',BASE+api],capture_output=True,text=True)
    try:
        rd=json.loads(io.open('_pr.json',encoding='utf-8').read())
        print(loc, 'HTTP', r2.stdout, 'sha', (rd.get('commit') or {}).get('sha','')[:7], rd.get('message',''))
    except Exception:
        print(loc, 'HTTP', r2.stdout, 'PARSE_ERR')
