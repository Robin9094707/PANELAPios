#!/usr/bin/env python3
"""Loopback-only synthetic TLS endpoint for iOS transport tests. No real server data."""
import http.server, ssl, json, hashlib
from pathlib import Path
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_GET(self):
        if self.headers.get('Authorization')!='Bearer synthetic-tls-token':
            self.reply(401,{'message':'Synthetic key missing'});return
        if self.path=='/v1/health':self.reply(200,{'serverId':'synthetic-tls-server','apiVersion':1,'scope':'read','version':'8.3.0'})
        elif self.path=='/v1/redirect':self.reply(302,{'message':'Must never follow'},location='https://never-contact.example.invalid/steal')
        else:self.reply(404,{'message':'Synthetic route missing'})
    def do_POST(self):
        if self.headers.get('Authorization')!='Bearer synthetic-tls-token':
            self.reply(401,{'message':'Synthetic key missing'});return
        if self.path!='/v1/plugin-files/echo' or self.headers.get('Content-Type')!='application/java-archive':
            self.reply(400,{'message':'Invalid binary fixture request'});return
        size=int(self.headers.get('Content-Length','0'))
        if not 0<size<=16*1024*1024:
            self.reply(413,{'message':'Invalid fixture body size'});return
        digest=hashlib.sha256();remaining=size
        while remaining:
            data=self.rfile.read(min(65536,remaining))
            if not data:self.reply(400,{'message':'Truncated binary upload'});return
            digest.update(data);remaining-=len(data)
        self.reply(200,{'size':size,'sha256':digest.hexdigest()})
    def reply(self,status,data,location=None):
        body=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close')
        if location:self.send_header('Location',location)
        self.end_headers();self.wfile.write(body);self.close_connection=True
    def log_message(self,*args):pass
root=Path(__file__).resolve().parent/'test-fixtures'
server=http.server.ThreadingHTTPServer(('127.0.0.1',49383),Handler)
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2;context.set_alpn_protocols(['http/1.1']);context.load_cert_chain(root/'pinned-api-cert.pem',root/'pinned-api-key.pem')
server.socket=context.wrap_socket(server.socket,server_side=True)
print('Synthetic TLS fixture ready',flush=True)
server.serve_forever()
