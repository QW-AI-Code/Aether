#!/usr/bin/env python3
"""Opt-in real Xray + Aether HTTPS checks. No Android TUN or Google API tests.
Run the private fixture export unit test first. Keep fixtures, identities and raw
logs OUTSIDE the checkout. Only anonymized outcomes may be copied into reports.
"""
import os, json, socket, subprocess, contextlib, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRIVATE = Path(os.environ['AETHER_CHAIN_FIXTURES']).resolve()
assert ROOT not in PRIVATE.parents and PRIVATE != ROOT, 'Private fixtures must be outside source'
XRAY = Path(os.environ['XRAY_BIN']).resolve()
ENGINE = Path(os.environ['AETHER_HOST_BIN']).resolve()
BUDGET = int(os.environ.get('AETHER_LIVE_BUDGET_SECONDS', '180'))
RESULT = {'scope': 'real host Xray + Aether, TLS-validated HTTPS; no Android TUN',
          'native_policy': 'custom TCP upstream, bounded 4-way scan, 20s per probe', 'samples': {}}

def probe(port):
    p = subprocess.run(['curl','--noproxy','','--proxy',f'socks5h://127.0.0.1:{port}',
        '--connect-timeout','6','--max-time','14','-sS','-o',os.devnull,'-w','%{http_code}',
        'https://www.gstatic.com/generate_204'],capture_output=True,text=True,timeout=18)
    return {'success':p.returncode == 0 and p.stdout[-3:] == '204',
            'curl_exit':p.returncode,'http_status':p.stdout[-3:]}

@contextlib.contextmanager
def process(args, label, env=None):
    with (PRIVATE/f'{label}.log').open('w') as log:
        p = subprocess.Popen([str(a) for a in args],cwd=PRIVATE,stdout=log,stderr=log,env=env)
        try: yield p
        finally:
            p.terminate()
            try: p.wait(3)
            except subprocess.TimeoutExpired: p.kill(); p.wait()

def ready(p, port, seconds):
    deadline=time.monotonic()+seconds
    while p.poll() is None and time.monotonic()<deadline:
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.4) as s:
                s.settimeout(.5);s.sendall(b'\x05\x01\x00')
                if s.recv(2) == b'\x05\x00': return True
        except OSError: pass
        time.sleep(.3)
    return False

def local_port(config): return json.loads(config.read_text())['inbounds'][0]['port']
def engine_args(folder, upstream=None):
    folder.mkdir(exist_ok=True)
    args=[ENGINE,'--bind','127.0.0.1:19081','--masque','--h2','--balanced','-4','--config',folder/'identity.toml']
    if upstream: args += ['--upstream',f'socks5://127.0.0.1:{upstream}']
    return args

def forward(name, config):
    port=local_port(config)
    with process([XRAY,'run','-c',config],f'{name}-first-xray') as xp:
        assert ready(xp,port,8), 'Xray local SOCKS handshake failed'
        env=dict(os.environ,AETHER_CUSTOM_TCP_UPSTREAM='1',AETHER_MASQUE_HTTP2='1')
        started=time.monotonic()
        with process(engine_args(PRIVATE/f'{name}-first-identity',port),f'{name}-first-engine',env) as ep:
            opened=ready(ep,19081,BUDGET)
            result=probe(19081) if opened else {'success':False,'reason':'engine_no_verified_gateway_listener',
                'engine_exit':ep.poll()}
        result['elapsed_seconds']=round(time.monotonic()-started,1)
        return result

# Exported source-authentic samples; choose a healthy representative, not one
# whose standalone HTTPS already failed. Retain every solo failure in results.
chosen=[]
for family in ['v2ray','npvt1','npvs']:
    paths=sorted((PRIVATE/'export').glob(f'{family}-*-solo.json'), key=lambda p:int(p.name.split('-')[1]))
    entry={'solo_attempts':[]};RESULT['samples'][family]=entry
    for i,config in enumerate(paths):
        with process([XRAY,'run','-c',config],f'{family}-solo-{i}') as p:
            outcome=probe(local_port(config)) if ready(p,local_port(config),8) else {'success':False,'reason':'no_local_socks'}
        entry['solo_attempts'].append({'fixture_index':int(config.name.split('-')[1]),**outcome})
        if outcome['success']:
            chosen.append((family,config));break
    if not any(name==family for name,_ in chosen):entry['config_to_aether']={'success':False,'reason':'no_healthy_standalone_representative'}
for name,config in chosen:
    RESULT['samples'][name]['config_to_aether']=forward(name,config)
    (PRIVATE/'current-live-results.json').write_text(json.dumps(RESULT,indent=2))
    print(name,'Config -> Aether:',json.dumps(RESULT['samples'][name]['config_to_aether']),flush=True)

# Independent synthetic local VLESS path separates app routing from a supplied
# remote server's destination policies. Same genuine Aether WARP and HTTPS test.
server={'log':{'loglevel':'none'},'inbounds':[{'listen':'127.0.0.1','port':19200,'protocol':'vless',
    'settings':{'clients':[{'id':'11111111-1111-4111-8111-111111111111'}],'decryption':'none'}}],
    'outbounds':[{'protocol':'freedom','settings':{}}]}
server_file=PRIVATE/'controlled-vless-server.json';server_file.write_text(json.dumps(server))
with process([XRAY,'run','-c',server_file],'controlled-vless-server') as sp:
    time.sleep(.8)
    RESULT['controlled_local_vless_to_real_aether']=forward('controlled-vless',PRIVATE/'export/local-first.json')
print('Controlled VLESS -> real Aether:',json.dumps(RESULT['controlled_local_vless_to_real_aether']),flush=True)

# Reverse regression: always supervise/probe the final Xray port, not base engine.
with process(engine_args(PRIVATE/'reverse-engine-identity'),'reverse-engine') as ep:
    opened=ready(ep,19081,BUDGET)
    for name,config in chosen:
        last=Path(str(config).replace('-solo.json','-last.json'))
        if opened:
            with process([XRAY,'run','-c',last],f'{name}-reverse-xray') as xp:
                outcome=probe(local_port(last)) if ready(xp,local_port(last),8) else {'success':False,'reason':'xray_not_ready'}
        else:outcome={'success':False,'reason':'base_engine_not_ready'}
        RESULT['samples'][name]['aether_to_config']=outcome
        print(name,'Aether -> Config:',json.dumps(outcome),flush=True)
(PRIVATE/'current-live-results.json').write_text(json.dumps(RESULT,indent=2)+'\n')
