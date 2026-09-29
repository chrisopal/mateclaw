import json
import threading
import unittest
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer

from app import Handler,REPORTS
from test_analysis import fixture


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True)
        cls.thread.start()
        cls.base=f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown();cls.server.server_close();cls.thread.join()

    def test_upload_readback_and_export(self):
        req=Request(self.base+'/api/analyze',fixture(),{'X-Filename':'test.xlsx','Content-Type':'application/octet-stream'})
        with urlopen(req) as response:r=json.load(response)
        with urlopen(self.base+'/api/result/'+r['id']) as response:self.assertEqual(json.load(response)['record_count'],4)
        for ext in ['xlsx','html']:
            with urlopen(self.base+'/api/export/'+r['id']+'.'+ext+'?product=P1&month=2025-02') as response:
                self.assertEqual(response.status,200)
                self.assertIn('attachment',response.headers['Content-Disposition'])
                self.assertTrue(len(response.read())>1000)

    def test_bad_upload_and_missing_report(self):
        for path,data,code in [('/api/analyze',b'bad',400),('/api/result/missing',None,404)]:
            with self.assertRaises(HTTPError) as raised:urlopen(Request(self.base+path,data))
            self.assertEqual(raised.exception.code,code)
            self.assertIn('errors',json.load(raised.exception))

    def test_reject_remote_origin_and_host(self):
        for header,value in [('Origin','https://example.org'),('Host','attacker.example')]:
            with self.assertRaises(HTTPError) as raised:urlopen(Request(self.base+'/api/sample',headers={header:value}))
            self.assertEqual(raised.exception.code,403)


if __name__=='__main__':unittest.main()
