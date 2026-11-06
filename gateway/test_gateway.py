import io,json,tempfile,pathlib,unittest,wave
from unittest.mock import patch,Mock
import server

def wav(rate=16000,channels=1,frames=3200):
 b=io.BytesIO()
 with wave.open(b,'wb') as w:w.setnchannels(channels);w.setsampwidth(2);w.setframerate(rate);w.writeframes(b'\0'*(frames*2*channels))
 return b.getvalue()
class Gateway(unittest.TestCase):
 def handler(self,path):
  h=object.__new__(server.Handler);h.path=path;h.send_json=Mock();h.connection=Mock();return h
 def test_wav(self):
  w=server.validate_wav(wav());self.assertEqual(w.getnframes(),3200);w.close()
 def test_bad_audio(self):
  for data in [b'bad',wav(rate=8000),wav(channels=2),wav(frames=10)]:
   with self.assertRaises(ValueError):server.validate_wav(data)
 def test_no_pairing(self):
  h=self.handler('/pair');h.do_GET();self.assertEqual(h.send_json.call_args.args[0],404)
 def test_no_request_log(self):
  with patch('builtins.print') as p:self.handler('/').log_message('%s','synthetic query');p.assert_not_called()
 def test_auth_before_body(self):
  h=self.handler('/v1/transcribe');h.headers={};h.rfile=Mock()
  with patch.object(server,'read_token',return_value='synthetic-token-for-test-only-00000'):h.do_POST()
  self.assertEqual(h.send_json.call_args.args[0],401);h.rfile.read.assert_not_called()
 def test_short_token(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'token';p.write_text('short')
   with patch.object(server,'TOKEN_FILE',p):
    with self.assertRaises(RuntimeError):server.read_token()
 def test_nonloopback_refused(self):
  with patch.object(server,'BIND','192.0.2.1'):
   with self.assertRaises(RuntimeError):server.main()
if __name__=='__main__':unittest.main()
