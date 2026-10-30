#!/usr/bin/env python3
"""Actual Xray payload/negative-routing tests, with a local instrumented SOCKS hop.
This is NOT a live WARP/Aether or Android TUN test. Set XRAY_BIN to the host core.
AETHER_CHAIN_FIXTURES/export must contain builder-generated local-first/last JSON.
No remote credentials are printed or committed.
"""
import os, socket, socketserver, struct, threading, subprocess, time, json, tempfile
from pathlib import Path
from contextlib import contextmanager

ROOT=Path(__file__).resolve().parent.parent
BIN=os.environ.get('XRAY_BIN')
FIXTURES=os.environ.get('AETHER_CHAIN_FIXTURES')
if not BIN or not FIXTURES: raise SystemExit('Set XRAY_BIN and AETHER_CHAIN_FIXTURES before running these opt-in tests.')

def readn(s,n):
    data=b''
    while len(data)<n:
        b=s.recv(n-len(data))
        if not b: raise ConnectionError('closed')
        data+=b
    return data

def request(port):
    with socket.create_connection(('127.0.0.1',port),timeout=3) as s:
        s.settimeout(5);s.sendall(b'\x05\x01\x00');assert readn(s,2)==b'\x05\x00'
        s.sendall(b'\x05\x01\x00\x01'+socket.inet_aton('127.0.0.1')+struct.pack('!H',19203))
        reply=readn(s,4);assert reply[1]==0
        if reply[3]==1:readn(s,6)
        elif reply[3]==4:readn(s,18)
        else:readn(s,readn(s,1)[0]+2)
        s.sendall(b'GET /routing-proof HTTP/1.0\r\nHost: localhost\r\n\r\n')
        data=b''
        while b'AETHER-ROUTING-PROOF' not in data:
            data+=readn(s,1)
            if len(data)>8192:raise ValueError('unexpected reply')
        return True

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True;daemon_threads=True
class HTTP(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.recv(4096);self.request.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 20\r\n\r\nAETHER-ROUTING-PROOF')
class Relay(socketserver.BaseRequestHandler):
    targets=[]
    def handle(self):
        try:
            s=self.request;s.settimeout(8)
            assert readn(s,1)==b'\x05';readn(s,readn(s,1)[0]);s.sendall(b'\x05\x00')
            h=readn(s,4);assert h[:3]==b'\x05\x01\x00'
            if h[3]==1:host=socket.inet_ntoa(readn(s,4))
            elif h[3]==3:host=readn(s,readn(s,1)[0]).decode()
            else:raise ValueError('unsupported test address')
            port=struct.unpack('!H',readn(s,2))[0];assert host in ['127.0.0.1','localhost'] and port==19200
            self.targets.append((host,port))
            with socket.create_connection((host,port),timeout=3) as out:
                s.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                def copy(a,b):
                    try:
                        while True:
                            data=a.recv(8192)
                            if not data:break
                            b.sendall(data)
                    except OSError:pass
                    finally:
                        try:b.shutdown(socket.SHUT_WR)
                        except OSError:pass
                t=threading.Thread(target=copy,args=(s,out),daemon=True);t.start();copy(out,s);t.join(1)
        except (OSError,AssertionError,ConnectionError,ValueError):pass

@contextmanager
def core(config):
    with tempfile.TemporaryDirectory() as d:
        f=Path(d)/'core.json';f.write_text(config if isinstance(config,str) else json.dumps(config))
        proc=subprocess.Popen([BIN,'run','-c',str(f)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        time.sleep(.4)
        try:
            assert proc.poll() is None,'Xray startup failed'
            yield
        finally:
            proc.terminate()
            try:proc.wait(3)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()

servercfg={'log':{'loglevel':'none'},'inbounds':[{'listen':'127.0.0.1','port':19200,'protocol':'vless','settings':{'clients':[{'id':'11111111-1111-4111-8111-111111111111'}],'decryption':'none'}}],'outbounds':[{'protocol':'freedom','settings':{}}]}
http=Server(('127.0.0.1',19203),HTTP);threading.Thread(target=http.serve_forever,daemon=True).start()
relay=Server(('127.0.0.1',19201),Relay);threading.Thread(target=relay.serve_forever,daemon=True).start()
proof={}
try:
    with core(servercfg):
        with core((Path(FIXTURES)/'export/local-first.json').read_text()):
            proof['first_tcp_payload_via_real_xray']=request(19202)
        with core((Path(FIXTURES)/'export/local-last.json').read_text()):
            proof['last_tcp_payload_via_real_xray_and_socks_hop']=request(19202)
            assert Relay.targets;proof['last_hop_observed_config_server_target']=True
            relay.shutdown();relay.server_close()
            try:request(19202)
            except Exception:proof['last_hop_failure_does_not_bypass_directly']=True
            else:raise AssertionError('dialerProxy was bypassed')
    with core((Path(FIXTURES)/'export/local-first.json').read_text()):
        try:request(19202)
        except Exception:proof['first_hop_failure_does_not_bypass_directly']=True
        else:raise AssertionError('config hop was bypassed')
finally:
    http.shutdown();http.server_close()
    try:relay.server_close()
    except OSError:pass
assert len(proof)==5 and all(proof.values())
print(json.dumps({'scope':'real Xray + local instrumented SOCKS hop, not live Aether','checks':proof},indent=2))
