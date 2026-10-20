#!/usr/bin/env python3
"""Loopback-only synthetic TLS endpoint for iOS transport tests. No real server data."""
import http.server, ssl, json
from pathlib import Path
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def do_GET(self):
        if self.headers.get('Authorization')!='Bearer synthetic-tls-token':
            self.reply(401,{'message':'Synthetic key missing'});return
        if self.path=='/v1/health':self.reply(200,{'serverId':'synthetic-tls-server','apiVersion':1,'scope':'read','version':'8.3.0'})
        elif self.path=='/v1/redirect':self.reply(302,{'message':'Must never follow'},location='https://never-contact.example.invalid/steal')
        else:self.reply(404,{'message':'Synthetic route missing'})
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
