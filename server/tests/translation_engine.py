"""Router behavior without downloading or loading model weights."""
import copy
import pathlib
import sys
import unittest
import json
import threading
from http.server import ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / 'ui'))
from translation_engine import Engine, Handler, normalize

CONFIG = {
 'default_model':'nllb', 'fallback_model':'opus', 'routes':{}, 'cache_entries':2,
 'context':{'enabled':True,'model':'qwen','max_segments':5},
 'models':{'nllb':{'backend':'nllb'},'opus':{'backend':'libretranslate'},'qwen':{'backend':'qwen'}}
}


class RouterTests(unittest.TestCase):
    def engine(self):
        engine=Engine(copy.deepcopy(CONFIG))
        engine.detect=Mock(return_value='en')
        engine.run_model=Mock(return_value='Xin chao')
        return engine

    def test_normalize_cache_and_metadata(self):
        self.assertEqual(normalize('  Cafe\u0301  \r\n\r\n hello\tworld  '),'Caf\u00e9\n\nhello world')
        engine=self.engine()
        result=engine.translate({'text':' hello ', 'target':'vi'})
        self.assertEqual((result['source'],result['target'],result['model']),('en','vi','nllb'))
        self.assertFalse(result['cached'])
        self.assertTrue(engine.translate({'text':'hello','target':'vi'})['cached'])
        self.assertEqual(engine.run_model.call_count,1)
        self.assertEqual(engine.metrics['cache_hits'],1)
        engine.translate({'text':'two','target':'vi'})
        engine.translate({'text':'three','target':'vi'})
        self.assertEqual(len(engine.cache),2)

    def test_pair_route_and_fallback(self):
        engine=self.engine()
        engine.config['routes']['en-vi']={'primary':'opus','fallback':'nllb'}
        engine.run_model.side_effect=[RuntimeError('failure'),'Recovered']
        result=engine.translate({'text':'hello','source':'en','target':'vi'})
        self.assertEqual(result['model'],'nllb')
        self.assertTrue(result['fallback'])
        self.assertEqual(engine.metrics['fallbacks'],1)
        engine.detect.assert_not_called()

    def test_context_is_bounded_and_in_cache_key(self):
        engine=self.engine()
        for previous in ('previous one','previous two'):
            result=engine.translate({'text':'hello','target':'vi','mode':'context','context':[previous]})
            self.assertEqual(result['model'],'qwen')
            self.assertEqual(engine.run_model.call_args.args[-1],[previous])
        self.assertEqual(engine.run_model.call_count,2)
        engine.config['context']['max_segments']=1
        engine.translate({'text':'other','target':'vi','mode':'context','context':['old','latest']})
        self.assertEqual(engine.run_model.call_args.args[-1],['latest'])
        with self.assertRaises(ValueError):
            engine.translate({'text':'hello','target':'vi','context':['x']*6})

    def test_format_placeholders_and_identity(self):
        engine=self.engine()
        engine.run_model.side_effect=lambda name,text,*args:text.replace('hello','xin chao')
        result=engine.translate({'text':'hello {name}\n\nhello {{user}} %s','source':'en','target':'vi'})
        self.assertEqual(result['text'],'xin chao {name}\n\nxin chao {{user}} %s')
        result=engine.translate({'text':'same {name}','source':'vi','target':'vi'})
        self.assertEqual(result['model'],'identity')
        self.assertEqual(result['text'],'same {name}')
        engine.run_model.return_value='unexpected {other}'
        engine.run_model.side_effect=None
        with self.assertRaises(RuntimeError):
            engine.translate({'text':'new {name}','source':'en','target':'vi'})
        self.assertEqual(engine.metrics['errors'],1)

    def test_native_opus_pivot_and_output_validation(self):
        engine=self.engine()
        engine.config['models']['opus']={'backend':'ct2_opus','path':'models/translation/opus'}
        # Exercise the real native adapter, not the router's stubbed model method.
        engine.run_model=Engine.run_model.__get__(engine,Engine)
        model=Mock()
        model.translate_batch.return_value=[SimpleNamespace(hypotheses=[['word','</s>']])]
        tokenizer=Mock()
        tokenizer.encode.return_value=['word']
        tokenizer.decode_pieces.return_value='\u2581hello_world'
        factory=Mock(return_value=model)
        with patch.dict(sys.modules,{'ctranslate2':SimpleNamespace(Translator=factory),
                                    'sentencepiece':SimpleNamespace(SentencePieceProcessor=Mock(return_value=tokenizer))}):
            self.assertEqual(engine.run_model('opus','input','vi','zh',[]),'hello_world')
            self.assertEqual(factory.call_count,2)
            self.assertEqual(model.translate_batch.call_count,2)
            self.assertEqual(factory.call_args.kwargs['compute_type'],'int8')
            self.assertEqual(model.translate_batch.call_args.kwargs['beam_size'],1)
            model.translate_batch.return_value=[SimpleNamespace(hypotheses=[['truncated']])]
            with self.assertRaises(RuntimeError):engine.run_model('opus','input','en','vi',[])
            tokenizer.encode.return_value=['x']*513
            with self.assertRaises(RuntimeError):engine.run_model('opus','input','en','vi',[])

    def test_http_boundary_and_metrics(self):
        with ThreadingHTTPServer(('127.0.0.1',0),Handler) as server:
            server.engine=self.engine()
            threading.Thread(target=server.serve_forever,daemon=True).start()
            base=f'http://127.0.0.1:{server.server_port}'
            def post(body,kind='application/json'):
                try:response=urlopen(Request(base+'/translate',body,{'Content-Type':kind}),timeout=2)
                except HTTPError as error:response=error
                with response:return response.status,json.load(response)
            try:
                self.assertEqual(post(b'{}','text/plain')[0],400)
                self.assertEqual(post(b'bad')[0],400)
                self.assertEqual(post(b'x'*131073)[0],413)
                payload=json.dumps({'text':'hello','target':'vi'}).encode()
                self.assertEqual(post(payload)[0],200)
                server.engine.lock.acquire()
                try:self.assertEqual(post(payload)[0],409)
                finally:server.engine.lock.release()
                with urlopen(base+'/metrics') as response:
                    metrics=json.load(response)
                    self.assertEqual(metrics['requests'],1)
                    self.assertNotIn('hello',json.dumps(metrics))
            finally:server.shutdown()

    def test_validation_and_busy(self):
        engine=self.engine()
        for data in ([],{}, {'text':'x','target':'xx'}, {'text':'x','target':'vi','source':[]},
                     {'text':'x','target':'vi','mode':[]}, {'text':'\x00','target':'vi'}):
            with self.assertRaises(ValueError):engine.translate(data)
        engine.lock.acquire()
        try:
            with self.assertRaises(BlockingIOError):engine.translate({'text':'hello','target':'vi'})
        finally:engine.lock.release()
        bad=copy.deepcopy(CONFIG);bad['routes']['en-vi']={'primary':'missing'}
        with self.assertRaises(ValueError):Engine(bad)


if __name__=='__main__':unittest.main()
