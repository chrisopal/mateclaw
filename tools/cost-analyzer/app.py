#!/usr/bin/env python3
"""A local-only, dependency-light web server for repeatable cost analysis."""
import argparse
import json
import secrets
import threading
import webbrowser
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

from analysis import MAX_BYTES, ValidationError, analyze, public_report
from reports import ROOT, excel, html_report, template

REPORTS=OrderedDict()
LOCK=threading.Lock()


def store(report):
    with LOCK:
        report['id']=secrets.token_hex(12)
        REPORTS[report['id']]=report
        while len(REPORTS)>8:REPORTS.popitem(last=False)
    return report


class Handler(BaseHTTPRequestHandler):
    def log_message(self,fmt,*args):
        # Avoid logging filenames or user-supplied URLs.
        return

    def send(self,data,status=200,kind='application/json; charset=utf-8',filename=None):
        if isinstance(data,dict):data=json.dumps(data,ensure_ascii=False,allow_nan=False).encode()
        self.send_response(status)
        self.send_header('Content-Type',kind)
        self.send_header('Content-Length',str(len(data)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        if filename:self.send_header('Content-Disposition',f'attachment; filename="{filename}"')
        self.end_headers();self.wfile.write(data)

    def allowed(self):
        host=self.headers.get('Host','')
        expected={f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}'}
        if host not in expected:
            self.send({'errors':['只允许从本机地址访问']},403);return False
        origin=self.headers.get('Origin')
        if origin and origin not in {f'http://{h}' for h in expected}:
            self.send({'errors':['不允许跨站访问本机分析服务']},403);return False
        if self.headers.get('Sec-Fetch-Site')=='cross-site':
            self.send({'errors':['不允许跨站访问本机分析服务']},403);return False
        return True

    def do_GET(self):
        if not self.allowed():return
        path=urlsplit(self.path).path
        args=parse_qs(urlsplit(self.path).query)
        try:
            if path=='/':return self.send((ROOT/'static/index.html').read_bytes(),kind='text/html; charset=utf-8')
            if path=='/favicon.ico':return self.send(b'',status=204,kind='image/x-icon')
            if path in ['/static/app.js','/static/style.css']:
                return self.send((ROOT/path.lstrip('/')).read_bytes(),kind='text/javascript; charset=utf-8' if path.endswith('.js') else 'text/css; charset=utf-8')
            if path=='/api/template':return self.send(template(),kind='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',filename='cost-input-template.xlsx')
            if path=='/api/sample-file':return self.send((ROOT/'examples/制造企业成本示例.xlsx').read_bytes(),kind='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',filename='cost-example.xlsx')
            if path=='/api/sample':
                report=store(analyze((ROOT/'examples/制造企业成本示例.xlsx').read_bytes(),'示例：1000条模拟成本数据.xlsx'))
                return self.send(public_report(report))
            if path.startswith('/api/result/'):
                key=path.rsplit('/',1)[-1]
                with LOCK:report=REPORTS.get(key)
                return self.send(public_report(report)) if report else self.send({'errors':['报告已过期或服务已重启，请重新上传。']},404)
            if path.startswith('/api/export/'):
                name=path.rsplit('/',1)[-1]
                key,ext=name.rsplit('.',1)
                with LOCK:report=REPORTS.get(key)
                if not report:return self.send({'errors':['报告已过期，请重新上传。']},404)
                product=args.get('product',[None])[0];month=args.get('month',[None])[0]
                if ext=='xlsx':return self.send(excel(report,product,month),kind='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',filename='cost-analysis.xlsx')
                if ext=='html':return self.send(html_report(report,product,month),kind='text/html; charset=utf-8',filename='cost-analysis.html')
            return self.send({'errors':['页面不存在']},404)
        except (ValueError,ValidationError) as exc:
            return self.send({'errors':exc.errors if isinstance(exc,ValidationError) else [str(exc)]},400)
        except Exception:
            import traceback;traceback.print_exc()
            return self.send({'errors':['报告生成失败，请检查服务终端或重新上传。']},500)

    def do_POST(self):
        if not self.allowed():return
        if self.path!='/api/analyze':return self.send({'errors':['接口不存在']},404)
        try:
            length=int(self.headers.get('Content-Length','0'))
            if length<=0 or length>MAX_BYTES:raise ValidationError('请上传不超过10MB的xlsx文件')
            filename=unquote(self.headers.get('X-Filename','成本数据.xlsx'))
            if not filename.lower().endswith('.xlsx'):raise ValidationError('只支持.xlsx文件')
            self.connection.settimeout(30)
            data=self.rfile.read(length)
            if len(data)!=length:raise ValidationError('文件传输不完整，请重试')
            report=store(analyze(data,filename[:200]))
            return self.send(public_report(report))
        except (ValidationError,ValueError,TimeoutError) as exc:
            return self.send({'errors':exc.errors if isinstance(exc,ValidationError) else [str(exc)]},400)
        except Exception:
            import traceback;traceback.print_exc()
            return self.send({'errors':['分析失败，请检查文件或服务终端。']},500)


def main():
    parser=argparse.ArgumentParser(description='本地制造成本分析工具')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--no-browser',action='store_true')
    args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    url=f'http://127.0.0.1:{server.server_port}'
    print(f'成本分析工具已启动：{url}\n关闭此终端或按 Ctrl+C 停止服务。',flush=True)
    if not args.no_browser:threading.Timer(.5,lambda:webbrowser.open(url)).start()
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()


if __name__=='__main__':main()
